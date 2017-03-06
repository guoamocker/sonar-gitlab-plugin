/*
 * SonarQube :: GitLab Plugin
 * Copyright (C) 2016-2017 Talanlabs
 * gabriel.allaigre@talanlabs.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.talanlabs.sonar.plugins.gitlab;

import org.sonar.api.batch.postjob.issue.PostJobIssue;
import org.sonar.api.batch.rule.Severity;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class Reporter {

    static final List<Severity> SEVERITIES = Arrays.asList(Severity.BLOCKER, Severity.CRITICAL, Severity.MAJOR, Severity.MINOR, Severity.INFO);

    private final GitLabPluginConfiguration gitLabPluginConfiguration;

    private int[] newIssuesBySeverity = new int[SEVERITIES.size()];
    private Map<Severity, List<ReportIssue>> reportIssuesMap = new EnumMap<>(Severity.class);
    private Map<Severity, List<ReportIssue>> notReportedOnDiffMap = new EnumMap<>(Severity.class);
    private int notReportedIssueCount = 0;

    public Reporter(GitLabPluginConfiguration gitLabPluginConfiguration) {
        super();

        this.gitLabPluginConfiguration = gitLabPluginConfiguration;
    }

    public void process(PostJobIssue postJobIssue, @Nullable String gitLabUrl, boolean reportedOnDiff) {
        ReportIssue reportIssue = new ReportIssue(postJobIssue, gitLabUrl, reportedOnDiff);
        List<ReportIssue> reportIssues = reportIssuesMap.computeIfAbsent(postJobIssue.severity(), k -> new ArrayList<>());
        reportIssues.add(reportIssue);

        increment(postJobIssue.severity());
        if (!reportedOnDiff) {
            notReportedIssueCount++;

            List<ReportIssue> notReportedOnDiffs = notReportedOnDiffMap.computeIfAbsent(postJobIssue.severity(), k -> new ArrayList<>());
            notReportedOnDiffs.add(reportIssue);
        }
    }

    private void increment(Severity severity) {
        this.newIssuesBySeverity[SEVERITIES.indexOf(severity)]++;
    }

    public boolean hasIssue() {
        return getIssueCount() > 0;
    }

    public String getStatus() {
        return isAboveGates() ? "failed" : "success";
    }

    public boolean isAboveGates() {
        return aboveImportantGates() || aboveOtherGates();
    }

    private boolean aboveImportantGates() {
        return aboveGateForSeverity(Severity.BLOCKER, gitLabPluginConfiguration.maxBlockerIssuesGate()) || aboveGateForSeverity(Severity.CRITICAL, gitLabPluginConfiguration.maxCriticalIssuesGate());
    }

    private boolean aboveOtherGates() {
        return aboveGateForSeverity(Severity.MAJOR, gitLabPluginConfiguration.maxMajorIssuesGate()) || aboveGateForSeverity(Severity.MINOR, gitLabPluginConfiguration.maxMinorIssuesGate())
                || aboveGateForSeverity(Severity.INFO, gitLabPluginConfiguration.maxInfoIssuesGate());
    }

    private boolean aboveGateForSeverity(Severity severity, int max) {
        return max != -1 && getIssueCountForSeverity(severity) > max;
    }

    public int getIssueCountForSeverity(Severity s) {
        return newIssuesBySeverity[SEVERITIES.indexOf(s)];
    }

    public int getNotReportedIssueCount() {
        return notReportedIssueCount;
    }

    public int getIssueCount() {
        return getIssueCountForSeverity(Severity.BLOCKER) + getIssueCountForSeverity(Severity.CRITICAL) + getIssueCountForSeverity(Severity.MAJOR) + getIssueCountForSeverity(Severity.MINOR) + getIssueCountForSeverity(Severity.INFO);
    }

    public List<ReportIssue> getReportIssues() {
        return Collections.unmodifiableList(SEVERITIES.stream().map(reportIssuesMap::get).filter(l -> l != null && !l.isEmpty()).flatMap(List::stream).collect(Collectors.toList()));
    }

    public List<ReportIssue> getReportIssuesForSeverity(Severity severity) {
        return Collections.unmodifiableList(reportIssuesMap.getOrDefault(severity, Collections.emptyList()));
    }

    public List<ReportIssue> getNotReportedOnDiffReportIssues() {
        return Collections.unmodifiableList(SEVERITIES.stream().map(notReportedOnDiffMap::get).filter(l -> l != null && !l.isEmpty()).flatMap(List::stream).collect(Collectors.toList()));
    }

    public List<ReportIssue> getNotReportedOnDiffReportIssueForSeverity(Severity severity) {
        return Collections.unmodifiableList(notReportedOnDiffMap.getOrDefault(severity, Collections.emptyList()));
    }

    public String getStatusDescription() {
        StringBuilder sb = new StringBuilder();
        printNewIssuesInline(sb);
        return sb.toString();
    }

    private void printNewIssuesInline(StringBuilder sb) {
        sb.append("SonarQube reported ");
        int newIssues = getIssueCount();
        if (newIssues > 0) {
            sb.append(newIssues).append(" issue").append(newIssues > 1 ? "s" : "").append(",");
            printNewIssuesInline(sb, Severity.BLOCKER, gitLabPluginConfiguration.maxBlockerIssuesGate());
            printNewIssuesInline(sb, Severity.CRITICAL, gitLabPluginConfiguration.maxCriticalIssuesGate());
            printNewIssuesInline(sb, Severity.MAJOR, gitLabPluginConfiguration.maxMajorIssuesGate());
            printNewIssuesInline(sb, Severity.MINOR, gitLabPluginConfiguration.maxMinorIssuesGate());
            printNewIssuesInline(sb, Severity.INFO, gitLabPluginConfiguration.maxInfoIssuesGate());
        } else {
            sb.append("no issues");
        }
    }

    private void printNewIssuesInline(StringBuilder sb, Severity severity, int max) {
        int issueCount = getIssueCountForSeverity(severity);
        if (issueCount > 0) {
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.append(" with ");
            } else {
                sb.append(" and ");
            }
            sb.append(issueCount).append(" ").append(severity.name().toLowerCase());
            if (max != -1 && issueCount > max) {
                sb.append(" (fail)");
            }
        }
    }

    public static class ReportIssue {

        private final PostJobIssue postJobIssue;
        private final String url;
        private final boolean reportedOnDiff;

        public ReportIssue(PostJobIssue postJobIssue, String url, boolean reportedOnDiff) {
            this.postJobIssue = postJobIssue;
            this.url = url;
            this.reportedOnDiff = reportedOnDiff;
        }

        public PostJobIssue getPostJobIssue() {
            return postJobIssue;
        }

        public String getUrl() {
            return url;
        }

        public boolean isReportedOnDiff() {
            return reportedOnDiff;
        }
    }
}
package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable diagnostic observations. Display prose never determines the health of a check. */
public final class DiagnosticReport {
  public enum Health {
    INFO,
    PASS,
    NEEDS_ATTENTION,
    UNVERIFIED
  }

  public static final class Check {
    public final String key, title, status, detail;
    public final Health health;

    public Check(String key, Health health, String title, String status, String detail) {
      this.key = Objects.requireNonNull(key);
      this.health = Objects.requireNonNull(health);
      this.title = Objects.requireNonNull(title);
      this.status = Objects.requireNonNull(status);
      this.detail = Objects.requireNonNull(detail);
    }
  }

  public final String heading, footer;
  public final List<Check> checks;

  public DiagnosticReport(String heading, List<Check> checks, String footer) {
    this.heading = Objects.requireNonNull(heading);
    this.checks = List.copyOf(checks);
    this.footer = Objects.requireNonNull(footer);
  }

  public String render() {
    StringBuilder out = new StringBuilder(heading);
    for (Check check : checks)
      out.append('\n')
          .append(check.title)
          .append(" · ")
          .append(check.status)
          .append('\n')
          .append(check.detail)
          .append('\n');
    return out.append(footer).toString();
  }

  public DiagnosticReport prepend(Check check) {
    List<Check> rows = new ArrayList<>();
    rows.add(check);
    rows.addAll(checks);
    return new DiagnosticReport(heading, rows, footer);
  }

  /** Parse only the successful, fixed version command's protocol, never a report or operation log. */
  public static final class ToolVersions {
    public final String node, npm, python;

    private ToolVersions(String node, String npm, String python) {
      this.node = node;
      this.npm = npm;
      this.python = python;
    }

    public static ToolVersions parse(String output) throws IOException {
      if (output == null || output.length() > 1500)
        throw new IOException("RUNTIME_TOOL_VERSIONS_SIZE");
      String node = null, npm = null, python = null;
      for (String line : output.split("\\r?\\n")) {
        if (line.startsWith("DSHA_TOOL_NODE=")) {
          if (node != null) throw new IOException("RUNTIME_TOOL_VERSIONS_DUPLICATE");
          node = line.substring(15);
        } else if (line.startsWith("DSHA_TOOL_NPM=")) {
          if (npm != null) throw new IOException("RUNTIME_TOOL_VERSIONS_DUPLICATE");
          npm = line.substring(14);
        } else if (line.startsWith("DSHA_TOOL_PYTHON=")) {
          if (python != null) throw new IOException("RUNTIME_TOOL_VERSIONS_DUPLICATE");
          python = line.substring(17);
        }
      }
      if (node == null
          || npm == null
          || python == null
          || !node.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
          || !npm.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
          || !python.matches("Python [0-9]+\\.[0-9]+\\.[0-9]+[A-Za-z0-9.+-]*"))
        throw new IOException("RUNTIME_TOOL_VERSIONS_INCOMPLETE");
      return new ToolVersions(node, npm, python);
    }

    public String detail() {
      return "Node: " + node + "\nnpm: " + npm + "\nPython: " + python;
    }
  }
}

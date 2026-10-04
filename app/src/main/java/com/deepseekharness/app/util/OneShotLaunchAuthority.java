package com.deepseekharness.app.util;

/** Pure, generation-bound authority for one managed ADB launch. */
public final class OneShotLaunchAuthority {
  private String ticket = "";
  private String command = "";
  private long generation = -1;
  private long expiresAt;
  private boolean planIssued;

  public synchronized boolean issue(
      String value, String exactCommand, long ownerGeneration, long now, long ttlMillis) {
    if (!ticket.isEmpty()
        || value == null
        || !value.matches("[a-f0-9]{48}")
        || exactCommand == null
        || exactCommand.isEmpty()
        || ownerGeneration < 0
        || now < 0
        || ttlMillis <= 0
        || Long.MAX_VALUE - now < ttlMillis) return false;
    ticket = value;
    command = exactCommand;
    generation = ownerGeneration;
    expiresAt = now + ttlMillis;
    planIssued = false;
    return true;
  }

  public synchronized String ticketFor(String exactCommand, long ownerGeneration, long now) {
    return current(ownerGeneration, now) && command.equals(exactCommand) ? ticket : "";
  }

  public synchronized boolean authorize(
      String value, String exactCommand, long ownerGeneration, long now) {
    if (!current(ownerGeneration, now)
        || planIssued
        || !ticket.equals(value)
        || !command.equals(exactCommand)) return false;
    planIssued = true;
    return true;
  }

  /** Consumes the lease immediately before the sole remote shell send. */
  public synchronized boolean commit(String value, long ownerGeneration, long now) {
    if (!current(ownerGeneration, now) || !planIssued || !ticket.equals(value)) return false;
    clear();
    return true;
  }

  public synchronized void cancel(long ownerGeneration) {
    if (!ticket.isEmpty() && generation == ownerGeneration) clear();
  }

  private boolean current(long ownerGeneration, long now) {
    if (ticket.isEmpty()) return false;
    if (now >= expiresAt) {
      clear();
      return false;
    }
    return generation == ownerGeneration;
  }

  private void clear() {
    ticket = "";
    command = "";
    generation = -1;
    expiresAt = 0;
    planIssued = false;
  }
}

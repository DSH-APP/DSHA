package com.deepseekharness.app.util;

/** Immutable identity of one native virtual-screen connection and displayed generation. */
public final class VirtualScreenRequestFence {
  public final long epoch;
  public final int port;
  public final String token;
  public final String generation;
  public final long revision;

  public VirtualScreenRequestFence(
      long epoch, int port, String token, String generation, long revision) {
    this.epoch = epoch;
    this.port = port;
    this.token = token;
    this.generation = generation;
    this.revision = revision;
  }

  public boolean matches(
      long lifecycle, long active, int port, String token, String generation, long revision) {
    return epoch == lifecycle
        && epoch == active
        && this.port == port
        && this.token.equals(token)
        && this.generation.equals(generation)
        && this.revision == revision;
  }
}

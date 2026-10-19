package com.xplanet.booking;

public class Problem extends RuntimeException {
  private final int status;

  public Problem(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static Problem conflict(String message) {
    return new Problem(409, message);
  }
}

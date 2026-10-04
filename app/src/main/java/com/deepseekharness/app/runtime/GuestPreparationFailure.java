package com.deepseekharness.app.runtime;

import java.io.IOException;

/** Typed preparation failures; legacy text adapters keep their historic public codes. */
final class GuestPreparationFailure extends IOException {
  enum Code {
    ENV_NOT_READY,
    BUNDLED_PYTHON_UNAVAILABLE
  }

  final Code code;

  GuestPreparationFailure(Code code) {
    super(code.name());
    this.code = code;
  }

  static boolean is(Throwable error, Code code) {
    return error instanceof GuestPreparationFailure
        && ((GuestPreparationFailure) error).code == code;
  }
}

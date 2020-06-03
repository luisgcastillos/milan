package com.amazon.milan.flink.runtime

class MilanFlinkRuntimeException(message: String, cause: Throwable)
  extends Exception(message, cause) {

  def this(message: String) {
    this(message, null)
  }
}

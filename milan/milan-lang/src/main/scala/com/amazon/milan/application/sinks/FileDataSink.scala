package com.amazon.milan.application.sinks

import com.amazon.milan.application.DataSink
import com.amazon.milan.dataformats.DataOutputFormat
import com.amazon.milan.typeutil.TypeDescriptor
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}


@JsonSerialize
@JsonDeserialize
class FileDataSink[T: TypeDescriptor](val path: String, val outputFormat: DataOutputFormat[T]) extends DataSink[T] {
  private var recordTypeDescriptor = implicitly[TypeDescriptor[T]]

  override def getGenericArguments: List[TypeDescriptor[_]] = List(this.recordTypeDescriptor)

  override def setGenericArguments(genericArgs: List[TypeDescriptor[_]]): Unit = {
    this.recordTypeDescriptor = genericArgs.head.asInstanceOf[TypeDescriptor[T]]
  }
}

package com.amazon.milan.dataformats

import java.io.OutputStream
import java.nio.charset.StandardCharsets

import com.amazon.milan.HashUtil
import com.amazon.milan.serialization.{JavaTypeFactory, ScalaObjectMapper}
import com.amazon.milan.typeutil.TypeDescriptor
import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}

import scala.collection.JavaConverters._


@JsonSerialize
@JsonDeserialize
class JsonDataOutputFormat[T: TypeDescriptor] extends DataOutputFormat[T] {
  @transient private lazy val objectMapper = new ScalaObjectMapper()
  @transient private lazy val javaType = new JavaTypeFactory(this.objectMapper.getTypeFactory).makeJavaType(this.recordTypeDescriptor)
  @transient private lazy val hashCodeValue = HashUtil.combineHashCodes(this.recordTypeDescriptor.hashCode())
  @transient private lazy val writer = this.objectMapper.writerFor(this.javaType)
  @transient private lazy val newLine = "\n".getBytes(StandardCharsets.UTF_8)

  private var recordTypeDescriptor = implicitly[TypeDescriptor[T]]

  override def getGenericArguments: List[TypeDescriptor[_]] =
    List(implicitly[TypeDescriptor[T]])

  override def setGenericArguments(genericArgs: List[TypeDescriptor[_]]): Unit = {
    this.recordTypeDescriptor = genericArgs.head.asInstanceOf[TypeDescriptor[T]]
  }

  override def writeValue(value: T, outputStream: OutputStream): Unit = {
    this.writer.writeValue(outputStream, value)
    outputStream.write(this.newLine)
  }

  override def writeValues(values: TraversableOnce[T], outputStream: OutputStream): Unit = {
    this.writer
      .withRootValueSeparator("\n")
      .writeValues(outputStream)
      .writeAll(values.toIterable.asJava)
    outputStream.write(this.newLine)
  }

  override def hashCode(): Int = this.hashCodeValue

  override def equals(obj: Any): Boolean = {
    obj match {
      case o: JsonDataOutputFormat[T] =>
        this.recordTypeDescriptor.equals(o.recordTypeDescriptor)

      case _ =>
        false
    }
  }
}

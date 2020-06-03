package com.amazon.milan.flink.generator

import java.time.Duration

import com.amazon.milan.SemanticVersion
import com.amazon.milan.application.sources.FileDataSource
import com.amazon.milan.dataformats._
import com.amazon.milan.flink.TypeUtil
import com.amazon.milan.flink.internal.{DefaultTypeEmitter, TypeEmitter}
import com.amazon.milan.flink.types._
import com.amazon.milan.flink.typeutil._
import com.amazon.milan.serialization.DataFormatConfiguration
import com.amazon.milan.typeutil.{BasicTypeDescriptor, CollectionTypeDescriptor, DataStreamTypeDescriptor, FieldDescriptor, GeneratedTypeDescriptor, GroupedStreamTypeDescriptor, JoinedStreamsTypeDescriptor, NumericTypeDescriptor, ObjectTypeDescriptor, TupleTypeDescriptor, TypeDescriptor}
import org.apache.commons.lang.StringEscapeUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.windowing.time.Time

import scala.collection.{AbstractSeq, immutable}
import scala.reflect.{ClassTag, classTag}


trait Raw {
  val value: String

  override def toString: String = this.value
}

object Raw {
  def unapply(arg: Raw): Option[String] = Some(arg.value)
}

case class RawList(l: List[String])

case class ClassName(value: String) extends Raw

case class ValName(value: String) extends Raw

case class CodeBlock(value: String) extends Raw {
  def indentTail(level: Int): CodeBlock = {
    CodeBlock(value.indentTail(level))
  }
}

object CodeBlock {
  val EMPTY = CodeBlock("")
}


/**
 * Provides methods for converting objects into the scala code that constructors those objects.
 *
 * @param typeEmitter                   The [[TypeEmitter]] to use for emitting type names.
 * @param preventGenericTypeInformation Specifies whether, when converting a TypeDescriptor to a Flink TypeInformation,
 *                                      it should cause a runtime error if the TypeInformation created is a GenericTypeInfo.
 */
class TypeLifter(val typeEmitter: TypeEmitter, preventGenericTypeInformation: Boolean) {

  def this(typeEmitter: TypeEmitter) {
    this(typeEmitter, false)
  }

  def this() {
    this(new DefaultTypeEmitter)
  }

  def withTypeEmitter(typeEmitter: TypeEmitter): TypeLifter = {
    new TypeLifter(typeEmitter, this.preventGenericTypeInformation)
  }

  implicit class Interpolator(sc: StringContext) {
    def q(subs: Any*): String = {
      val partsIterator = sc.parts.iterator
      val subsIterator = subs.iterator

      val sb = new StringBuilder(partsIterator.next())

      var unrollNextPart = false
      var unrollSeparator = ""

      while (subsIterator.hasNext) {
        val lifted =
          if (unrollNextPart) {
            liftSequence(subsIterator.next(), unrollSeparator)
          }
          else {
            lift(subsIterator.next())
          }
        sb.append(lifted)

        val nextPart = partsIterator.next()
        if (nextPart.endsWith("..")) {
          unrollNextPart = true
          unrollSeparator = ", "
          sb.append(nextPart.substring(0, nextPart.length - 2))
        }
        else if (nextPart.endsWith("//")) {
          unrollNextPart = true
          unrollSeparator = "\n"
          sb.append(nextPart.substring(0, nextPart.length - 2))
        }
        else {
          unrollNextPart = false
          sb.append(nextPart)
        }
      }

      sb.toString()
    }

    def qc(subs: Any*): CodeBlock = {
      val value = q(subs: _*).strip
      CodeBlock(value)
    }

    def qn(subs: Any*): ClassName = {
      val value = q(subs: _*).strip
      ClassName(value)
    }
  }

  def nameOf[T: ClassTag]: ClassName =
    ClassName(this.toCanonicalName(classTag[T].runtimeClass.getName))

  def code(s: String): CodeBlock = CodeBlock(s)

  def raw(l: List[String]): RawList = RawList(l)

  def liftSequence(o: Any, separator: String): String = {
    o match {
      case t: AbstractSeq[_] => t.map(lift).mkString(separator)
      case _ => throw new IllegalArgumentException(s"Object of type '${o.getClass.getTypeName}' is not a sequence.")
    }
  }

  def lift(o: Any): String =
    o match {
      case t: TypeDescriptor[_] => liftTypeDescriptor(t)
      case t: FieldDescriptor[_] => q"new ${nameOf[FieldDescriptor[Any]]}[${t.fieldType.toTerm}](${t.name}, ${t.fieldType})"
      case t: DataInputFormat[_] => liftDataInputFormat(t)
      case t: DataOutputFormat[_] => liftDataOutputFormat(t)
      case t: DataFormatConfiguration => q"new ${nameOf[DataFormatConfiguration]}(${t.flags})"
      case t: Array[_] => s"Array(${t.map(lift).mkString(", ")})"
      case t: List[_] => s"List(${t.map(lift).mkString(", ")})"
      case t: immutable.HashSet[_] => s"scala.collection.immutable.HashSet(${t.map(lift).mkString(", ")})"
      case t: Map[_, _] => s"Map(${t.map { case (k, v) => q"$k -> $v" }.mkString(", ")})"
      case Raw(s) => s
      case RawList(l) => l.mkString("List(", ", ", ")")
      case t: Char => "0x" + t.toHexString
      case t: String => if (t == null) "null" else "\"" + StringEscapeUtils.escapeJava(t) + "\""
      case t: Boolean => t.toString
      case t: Int => t.toString
      case t: Long => t.toString
      case t: FileDataSource.Configuration => q"${nameOf[FileDataSource.Configuration]}(${t.readMode})"
      case t if t == null => "null"
      case t if t.getClass.getTypeName == "scala.Enumeration$Val" => liftEnumeration(t)
      case t: SemanticVersion => q"new ${nameOf[SemanticVersion]}(${t.major}, ${t.minor}, ${t.patch}, ${t.preRelease}, ${t.buildMetadata})"
      case t: Time => q"${nameOf[Time]}.milliseconds(${t.toMilliseconds})"
      case t: Duration => q"${nameOf[Duration]}.ofSeconds(${t.getSeconds}, ${t.getNano})"
      case t: Option[_] => this.liftOption(t)
      case t: CsvDataOutputFormat.Configuration => q"new ${nameOf[CsvDataOutputFormat.Configuration]}(${t.schema}, ${t.writeHeader}, ${t.dateTimeFormats})"
      case _ => throw new IllegalArgumentException(s"Can't lift object of type '${o.getClass.getTypeName}'.")
    }

  def liftTypeDescriptorToTypeInformation(typeDescriptor: TypeDescriptor[_]): CodeBlock = {
    if (typeDescriptor.isTupleRecord) {
      // This is a tuple type with named fields, which means it's a record type for a stream.
      // For this we use TupleStreamTypeInformation.
      val fieldInfos = typeDescriptor.fields.map(field => this.liftFieldDescriptorToFieldTypeInformation(field)).toArray
      qc"new ${nameOf[ArrayRecordTypeInformation]}($fieldInfos)"
    }
    else if (typeDescriptor.typeName == nameOf[RecordWrapper[Any, Product]].value) {
      val valueType = typeDescriptor.genericArguments.head
      val valueTypeInfo = this.liftTypeDescriptorToTypeInformation(valueType)
      val keyType = typeDescriptor.genericArguments.last
      val keyTypeInfo = this.liftTypeDescriptorToTypeInformation(keyType)
      qc"new ${nameOf[RecordWrapperTypeInformation[Any, Product]]}[${classname(valueType)}, ${classname(keyType)}]($valueTypeInfo, $keyTypeInfo)"
    }
    else if (typeDescriptor.isTuple) {
      if (typeDescriptor.genericArguments.isEmpty) {
        // This is an empty tuple, which doesn't really exist in Scala, but we represent as the Product type.
        qc"new ${nameOf[NoneTypeInformation]}"
      }
      else {
        // This is a tuple type, which we want to expose to Flink using Flink's TupleTypeInfo.
        val tupleClassName = ClassName(TypeUtil.getTupleTypeName(typeDescriptor.genericArguments.map(typeEmitter.getTypeFullName)))
        val elementTypeInfos = typeDescriptor.genericArguments.map(this.liftTypeDescriptorToTypeInformation).toArray
        qc"new ${nameOf[ScalaTupleTypeInformation[Product]]}[$tupleClassName]($elementTypeInfos)"
      }
    }
    else {
      val createTypeInfo =
        if (this.isNestedGenericType(typeDescriptor)) {
          // Nested generic types can be confusing to createTypeInformation, so in those cases we'll use
          // TypeInformation.of, which is not as good but at least won't cause a runtime error.
          CodeBlock(s"${nameOf[TypeInformation[Any]]}.of(classOf[${typeDescriptor.getFlinkTypeFullName}])")
        }
        else {
          CodeBlock(s"org.apache.flink.api.scala.createTypeInformation[${typeDescriptor.getFlinkTypeFullName}]")
        }

      val baseTypeInfo =
        if (this.preventGenericTypeInformation) {
          CodeBlock(s"com.amazon.milan.flink.runtime.RuntimeUtil.preventGenericTypeInformation($createTypeInfo)")
        }
        else {
          createTypeInfo
        }

      // createTypeInformation will not produce a TypeInformation that exposes the generic type parameters.
      // We need to create TypeInformation for the type parameters ourselves, and then create a TypeInformation that
      // exposes them.
      if (typeDescriptor.genericArguments.isEmpty) {
        baseTypeInfo
      }
      else {
        val typeParameters = typeDescriptor.genericArguments.map(this.liftTypeDescriptorToTypeInformation)
        qc"new ${nameOf[ParameterizedTypeInfo[Any]]}[${classname(typeDescriptor)}]($baseTypeInfo, $typeParameters)"
      }
    }
  }

  def liftFieldDescriptorToFieldTypeInformation(fieldDescriptor: FieldDescriptor[_]): CodeBlock = {
    qc"${nameOf[FieldTypeInformation]}(${fieldDescriptor.name}, ${liftTypeDescriptorToTypeInformation(fieldDescriptor.fieldType)})"
  }

  def getTupleClassName(elementCount: Int): ClassName =
    ClassName(TypeUtil.getTupleClassName(elementCount))

  def getTupleCreationStatement(elements: List[CodeBlock]): CodeBlock = {
    qc"${this.getTupleClassName(elements.length)}(..$elements)"
  }

  private def classname(ty: TypeDescriptor[_]): ClassName = {
    ClassName(this.typeEmitter.getTypeFullName(ty))
  }

  private def liftOption(optionValue: Option[_]): String = {
    optionValue match {
      case Some(value) => q"Some($value)"
      case None => "None"
    }
  }

  private def liftEnumeration(value: Any): String = {
    val enumTypeName = value.getClass.getMethod("scala$Enumeration$Val$$$outer").invoke(value).getClass.getCanonicalName
    this.toCanonicalName(enumTypeName) + value.toString
  }

  private def liftTypeDescriptor(value: TypeDescriptor[_]): String =
    value match {
      case t: NumericTypeDescriptor[_] => q"new ${nameOf[NumericTypeDescriptor[Any]]}[${classname(t)}](${t.typeName})"
      case t: TupleTypeDescriptor[_] => q"new ${nameOf[TupleTypeDescriptor[Any]]}[${classname(t)}](${t.typeName}, ${t.genericArguments}, ${t.fields})"
      case t: ObjectTypeDescriptor[_] => q"new ${nameOf[ObjectTypeDescriptor[Any]]}[${classname(t)}](${t.typeName}, ${t.genericArguments}, ${t.fields})"
      case t: GeneratedTypeDescriptor[_] => s"${nameOf[TypeDescriptor[Any]]}.of[${t.fullName}]"
      case t: CollectionTypeDescriptor[_] => q"new ${nameOf[CollectionTypeDescriptor[Any]]}[${classname(t)}](${t.typeName}, ${t.genericArguments})"
      case t: DataStreamTypeDescriptor => q"new ${nameOf[DataStreamTypeDescriptor]}(${t.recordType})"
      case t: JoinedStreamsTypeDescriptor => q"new ${nameOf[JoinedStreamsTypeDescriptor]}(${t.leftRecordType}, ${t.rightRecordType})"
      case t: GroupedStreamTypeDescriptor => q"new ${nameOf[GroupedStreamTypeDescriptor]}(${t.recordType})"
      case t: BasicTypeDescriptor[_] => q"new ${nameOf[BasicTypeDescriptor[Any]]}[${classname(t)}](${t.typeName})"
    }

  private def liftDataInputFormat(value: DataInputFormat[_]): String = {
    val recordType = value.getGenericArguments.head

    val defaultTypeLifter = this.withTypeEmitter(new DefaultTypeEmitter)
    val recordTypeName = ClassName(defaultTypeLifter.typeEmitter.getTypeFullName(recordType))
    val recordTypeStatement = CodeBlock(defaultTypeLifter.lift(recordType))

    value match {
      case t: JsonDataInputFormat[_] => q"new ${nameOf[JsonDataInputFormat[Any]]}[$recordTypeName](${t.config})($recordTypeStatement)"
      case t: CsvDataInputFormat[_] => q"new ${nameOf[CsvDataInputFormat[Any]]}[$recordTypeName](${t.schema}, ${t.skipHeader}, ${t.columnSeparator}, ${t.nullIdentifier}, ${t.config})($recordTypeStatement)"
    }
  }

  private def liftDataOutputFormat(value: DataOutputFormat[_]): String = {
    val recordType = value.getGenericArguments.head

    val defaultTypeLifter = this.withTypeEmitter(new DefaultTypeEmitter)
    val recordTypeName = ClassName(defaultTypeLifter.typeEmitter.getTypeFullName(recordType))
    val recordTypeStatement = CodeBlock(defaultTypeLifter.lift(recordType))

    value match {
      case t: CsvDataOutputFormat[_] => q"new ${nameOf[CsvDataOutputFormat[Any]]}[$recordTypeName](${t.config})($recordTypeStatement)"
      case t: JsonDataOutputFormat[_] => q"new ${nameOf[JsonDataOutputFormat[Any]]}[$recordTypeName]()($recordTypeStatement)"
    }
  }

  private def toCanonicalName(typeName: String): String =
    typeName.replace('$', '.')

  /**
   * Gets whether a type contains more than one level of nested generic arguments.
   *
   * @param ty A type descriptor.
   * @return True if the type is generic and one of its generic arguments is also generic, otherwise false.
   */
  private def isNestedGenericType(ty: TypeDescriptor[_]): Boolean = {
    ty.genericArguments.exists(_.genericArguments.nonEmpty)
  }

}

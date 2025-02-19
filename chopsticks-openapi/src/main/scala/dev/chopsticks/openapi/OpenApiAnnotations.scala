package dev.chopsticks.openapi

import sttp.tapir.Validator

import scala.annotation.StaticAnnotation

object OpenApiAnnotations {
  final case class entityName(name: String) extends StaticAnnotation
  final case class default[A](value: A, encodedValue: Option[Any] = None) extends StaticAnnotation
  final case class description(text: String) extends StaticAnnotation
  final case class validate[T](v: Validator[T]) extends StaticAnnotation
  final case class sumTypeSerDeStrategy[A](value: OpenApiSumTypeSerDeStrategy[A]) extends StaticAnnotation
  final case class jsonCaseConverter(from: OpenApiNamingConvention, to: OpenApiNamingConvention)
      extends StaticAnnotation
  final case class jsonEncoder[A](value: io.circe.Encoder[A]) extends StaticAnnotation
  final case class jsonDecoder[A](value: io.circe.Decoder[A]) extends StaticAnnotation
  final case class tapirSchema[A](value: sttp.tapir.Schema[A]) extends StaticAnnotation
}

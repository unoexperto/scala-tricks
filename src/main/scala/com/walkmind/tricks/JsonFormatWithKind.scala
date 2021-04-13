package com.walkmind.tricks

import spray.json.{JsNumber, JsObject, JsString, JsValue, JsonFormat, RootJsonFormat}

abstract class JsonFormatWithTypedKind[D, T](fieldName: String)(implicit typeFormat: JsonFormat[D]) extends RootJsonFormat[T] {

  protected def getKindOpt(json: JsValue): Option[D] =
    json match {
      case JsObject(map) => map.get(fieldName).map(typeFormat.read)
      case _ => None
    }

  protected def getKind(json: JsValue): D =
    getKindOpt(json).getOrElse(throw new RuntimeException(s"Can't find $fieldName field"))

  @inline
  protected def isKind(json: JsValue, name: String): Boolean =
    getKindOpt(json).contains(name)

  def addKind(json: JsValue, kind: D): JsValue =
    json match {
      case JsObject(map) => JsObject(map.updated(fieldName, typeFormat.write(kind)))
      case _ => json
    }
}

abstract class JsonFormatWithKind[T](fieldName: String) extends RootJsonFormat[T] {

  protected def getKindOpt(json: JsValue): Option[String] =
    json match {
      case JsObject(map) => map.get(fieldName).collect { case JsString(x) => x }
      case _ => None
    }

  protected def getKind(json: JsValue): String =
    getKindOpt(json).getOrElse(throw new RuntimeException(s"Can't find $fieldName field"))

  @inline
  protected def isKind(json: JsValue, name: String): Boolean =
    getKindOpt(json).contains(name)

  def addKind(json: JsValue, name: String): JsValue =
    json match {
      case JsObject(map) => JsObject(map.updated(fieldName, JsString(name)))
      case _ => json
    }
}

abstract class JsonFormatWithIntKind[T](fieldName: String) extends RootJsonFormat[T] {

  protected def getKindOpt(json: JsValue): Option[Int] =
    json match {
      case JsObject(map) => map.get(fieldName).collect { case JsNumber(x) => x.toInt }
      case _ => None
    }

  protected def getKind(json: JsValue): Int =
    getKindOpt(json).getOrElse(throw new RuntimeException(s"Can't find $fieldName field"))

  @inline
  protected def isKind(json: JsValue, value: Int): Boolean =
    getKindOpt(json).contains(value)

  def addKind(json: JsValue, value: Int): JsValue =
    json match {
      case JsObject(map) => JsObject(map.updated(fieldName, JsNumber(value)))
      case _ => json
    }
}
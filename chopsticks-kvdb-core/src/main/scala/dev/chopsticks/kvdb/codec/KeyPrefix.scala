package dev.chopsticks.kvdb.codec

import com.typesafe.scalalogging.StrictLogging
import dev.chopsticks.kvdb.codec.KeySerdes.flatten
import dev.chopsticks.kvdb.util.UnusedImplicits._
import shapeless.ops.hlist.{FlatMapper, IsHCons, Length, Take}
import shapeless.{<:!<, =:!=, Generic, HList, Nat}

import scala.annotation.implicitNotFound

@implicitNotFound(msg = "Cannot prove that ${A} is a DbKeyPrefix of ${B}.")
trait KeyPrefix[-A, B] {
  def serialize(a: A): Array[Byte]
}

object KeyPrefix extends StrictLogging {
  final private class KeyPrefixWithSerializer[A, B](serializer: KeySerializer[A]) extends KeyPrefix[A, B] {
    def serialize(a: A): Array[Byte] = serializer.serialize(a)
  }

  implicit def selfKeyPrefix[A](implicit deserializer: KeySerializer[A]): KeyPrefix[A, A] =
    new KeyPrefixWithSerializer(deserializer)

  // e.g
  // T as prefix of:
  // case class Bar(one: T, two: Boolean, three: Double)
  implicit def anyToKeyPrefixOfProduct[A, B <: Product, F <: HList, T <: HList](
    implicit
    f: KeySerdes.Aux[B, F],
    t: IsHCons.Aux[F, A, T],
    e: A <:!< Product,
    serializer: KeySerializer[A]
  ): KeyPrefix[A, B] = {
    logger.debug(s"[DbKeyPrefix][anyToDbKeyPrefixOfProduct] ${f.describe}")
    t.unused()
    e.unused()
    new KeyPrefixWithSerializer[A, B](serializer)
  }

  implicit def productToKeyPrefix[
    Prefix <: Product,
    Key <: Product,
    PrefixHlist <: HList,
    PrefixFlattenedHlist <: HList,
    KeyHlist <: HList,
    N <: Nat,
    TakenHlist <: HList
  ](
    implicit
    neqEvidence: Prefix =:!= Key,
    prefixHlist: Generic.Aux[Prefix, PrefixHlist],
    prefixFlattenedHlist: FlatMapper.Aux[flatten.type, PrefixHlist, PrefixFlattenedHlist],
    length: Length.Aux[PrefixFlattenedHlist, N],
    keyHlist: KeySerdes.Aux[Key, KeyHlist],
    takenHlist: Take.Aux[KeyHlist, N, TakenHlist],
    evidence: PrefixFlattenedHlist =:= TakenHlist,
    encoder: KeySerializer[Prefix]
  ): KeyPrefix[Prefix, Key] = {
    logger.debug(s"[DbKeyPrefix][productToDbKeyPrefix] ${keyHlist.describe}")
    //    n.unused()
    neqEvidence.unused()
    prefixHlist.unused()
    prefixFlattenedHlist.unused()
    length.unused()
    takenHlist.unused()
    evidence.unused()
    new KeyPrefixWithSerializer[Prefix, Key](encoder)
  }

  // e.g
  // String :: Boolean :: HNil
  //    as prefix of:
  // case class Bar(one: String, two: Boolean, three: Double)
//  implicit def hlistToKeyPrefix[A <: HList, B <: Product, F <: HList, N <: Nat, T <: HList](
//    implicit
//    l: Length.Aux[A, N],
//    f: KeySerdes.Aux[B, F],
//    t: Take.Aux[F, N, T],
//    e: A =:= T,
//    encoder: KeySerializer[A]
//  ): KeyPrefix[A, B] = {
//    logger.debug(s"[DbKeyPrefix][hlistToDbKeyPrefix] ${f.describe}")
//    l.unused()
//    t.unused()
//    e.unused()
//    new KeyPrefixWithSerializer[A, B](encoder)
//  }

  def apply[A, B](implicit e: KeyPrefix[A, B]): KeyPrefix[A, B] = e

//  implicit val literalStringDbKeyPrefix: DbKeyPrefix[String, String] = (a: String) =>
//    KvdbSerdesUtils.stringToByteArray(a)
}

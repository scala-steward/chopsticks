package dev.chopsticks.fdb.transaction

import dev.chopsticks.kvdb.ColumnFamily
import dev.chopsticks.kvdb.codec.KeyConstraints
import dev.chopsticks.kvdb.codec.KeyConstraints.ConstraintsBuilder
import dev.chopsticks.kvdb.fdb.FdbReadApi
import dev.chopsticks.kvdb.util.KvdbAliases.KvdbPair
import eu.timepit.refined.types.numeric.PosInt
import zio.{Task, ZIO}

class ZFdbKeyspaceReadApi[BCF[A, B] <: ColumnFamily[A, B], CF <: BCF[K, V], K, V](
  keyspace: CF,
  api: FdbReadApi[BCF]
) {
  def getRaw(
    constraints: ConstraintsBuilder[K]
  ): Task[Option[KvdbPair]] = {
    ZIO
      .fromCompletionStage(api.get(keyspace, KeyConstraints.build(constraints)(keyspace.keySerdes).constraints))
  }

  def get(
    constraints: ConstraintsBuilder[K]
  ): Task[Option[(K, V)]] = {
    getRaw(constraints)
      .flatMap(maybePair => ZIO.attempt(maybePair.map(p => keyspace.unsafeDeserialize(p))))
  }

  def getValue(
    constraints: ConstraintsBuilder[K]
  ): Task[Option[V]] = {
    ZIO
      .fromCompletionStage(api.get(keyspace, KeyConstraints.build(constraints)(keyspace.keySerdes).constraints))
      .flatMap(maybePair => ZIO.attempt(maybePair.map(p => keyspace.unsafeDeserializeValue(p._2))))
  }

  def getRangeRaw(
    from: ConstraintsBuilder[K],
    to: ConstraintsBuilder[K],
    limit: PosInt
  ): Task[List[KvdbPair]] = {
    ZIO
      .fromCompletionStage(api.getRange(keyspace, KeyConstraints.range(from, to, limit.value)(keyspace.keySerdes)))
  }

  def getRange(
    from: ConstraintsBuilder[K],
    to: ConstraintsBuilder[K],
    limit: PosInt
  ): Task[List[(K, V)]] = {
    getRangeRaw(from, to, limit)
      .flatMap(pairs => ZIO.attempt(pairs.map(p => keyspace.unsafeDeserialize(p))))
  }
}

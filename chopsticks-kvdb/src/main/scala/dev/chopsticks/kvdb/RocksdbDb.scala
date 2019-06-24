package dev.chopsticks.kvdb

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ArrayList => JavaArrayList}

import akka.NotUsed
import akka.stream.Attributes
import akka.stream.scaladsl.{Merge, Source}
import better.files.File
import cats.syntax.show._
import com.google.protobuf.{ByteString => ProtoByteString}
import com.typesafe.scalalogging.StrictLogging
import enumeratum.EnumEntry.Snakecase
import enumeratum.{Enum, EnumEntry}
import dev.chopsticks.kvdb.codec.DbKey
import dev.chopsticks.kvdb.codec.DbKeyConstraints.Implicits._
import dev.chopsticks.kvdb.DbInterface._
import dev.chopsticks.fp.AkkaEnv
import dev.chopsticks.proto.db.DbKeyConstraint.Operator
import dev.chopsticks.proto.db._
import dev.chopsticks.kvdb.util.DbUtils._
import dev.chopsticks.kvdb.util.RocksdbCFBuilder.RocksdbCFOptions
import dev.chopsticks.kvdb.util.RocksdbUtils._
import dev.chopsticks.kvdb.util.{RocksdbCFBuilder, RocksdbUtils}
import org.rocksdb._
import scalaz.zio.blocking._
import scalaz.zio.clock.Clock
import scalaz.zio.internal.Executor
import scalaz.zio.{Task, TaskR, UIO, ZIO, ZSchedule}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.language.higherKinds
import scala.util.Failure

object RocksdbDb extends StrictLogging {
  private def byteArrayToString(bytes: Array[Byte]): String = {
    new String(bytes, UTF_8)
  }

  private val ESTIMATE_NUM_KEYS = "rocksdb.estimate-num-keys"

  sealed trait MemoryUsageType extends EnumEntry with Snakecase

  object MemoryUsageType extends Enum[MemoryUsageType] {
    //noinspection TypeAnnotation
    val values = findValues

    case object ActiveMemTables extends MemoryUsageType

    case object NumEntriesActiveMemTable extends MemoryUsageType

    case object UnflushedMemTables extends MemoryUsageType

    case object PinnedMemTables extends MemoryUsageType

    case object TableReaders extends MemoryUsageType

    case object EstimateTableReadersMem extends MemoryUsageType

    case object EstimateLiveDataSize extends MemoryUsageType
  }

  final case class RocksdbLocalDbHistogram(median: Double, p95: Double, p99: Double, average: Double, stdDev: Double)

  final case class RocksdbLocalDbStats(
    tickers: Map[TickerType, Long],
    histograms: Map[HistogramType, RocksdbLocalDbHistogram],
    memoryUsage: Map[MemoryUsageType, Long]
  )

  final case class DbReferences[BaseCol[K, V] <: DbColumn[K, V]](
    db: RocksDB,
    columnHandleMap: Map[BaseCol[_, _], ColumnFamilyHandle],
    columnPrefixExtractorOptionMap: Map[BaseCol[_, _], String],
    stats: Statistics
  ) {
    def getColumnHandle[Col <: BaseCol[_, _]](column: Col): ColumnFamilyHandle = {
      columnHandleMap.getOrElse(
        column,
        throw InvalidDbArgumentException(
          s"Column family: $column doesn't exist in columnHandleMap: $columnHandleMap"
        )
      )
    }

    def validateColumnPrefixSeekOperation[Col <: BaseCol[_, _]](column: Col, prefix: Array[Byte]): Option[Throwable] = {
      if (prefix.nonEmpty) {
        val prefixString = byteArrayToString(prefix)

        columnPrefixExtractorOptionMap(column) match {
          case "nullptr" | "rocksdb.Noop" =>
            Some(
              UnoptimizedDbOperationException(
                s"Iterating with prefix $prefixString on column ${column.entryName} but this column " +
                  s"is not optimized for prefix seeking (no prefix extractor)"
              )
            )
          case o if o.contains("rocksdb.FixedPrefix.") =>
            val length = prefix.length
            val configuredLength = o.drop("rocksdb.FixedPrefix.".length).toInt
            if (length < configuredLength) {
              Some(
                UnoptimizedDbOperationException(
                  s"Iterating with prefix $prefixString (length = $length) on column ${column.entryName} but the configured " +
                    s"prefix extractor min length is $configuredLength"
                )
              )
            }
            else None
          case _ =>
            None
        }
      }
      else None
    }
  }

  private def readColumnFamilyOptionsFromDisk(dbPath: String): TaskR[Blocking, Map[String, Map[String, String]]] = {
    blocking(Task {
      val fileList = File(dbPath).list
      val prefix = "OPTIONS-"
      val optionsFiles = fileList.filter(_.name.startsWith(prefix))

      if (optionsFiles.isEmpty) {
        throw new RuntimeException(s"No options file found. List of files: ${fileList.mkString(", ")}")
      }

      optionsFiles.maxBy(_.name.drop(prefix.length).toInt)
    }).map { f =>
        logger.info(s"Latest db options file: ${f.pathAsString}")
        f
      }
      .map(_.lines)
      .map(RocksdbUtils.parseOptions)
      .map { sections =>
        val columnFamilyRegex = """CFOptions "([^"]+)"""".r
        sections.collect {
          case OptionsFileSection(columnFamilyRegex(name, _*), m) =>
            (name, m)
        }.toMap
      }
  }

  //  private object StreamEndMarker

  def apply[DbDef <: DbDefinition](
    definition: DbDef,
    path: String,
    options: Map[DbDef#BaseCol[_, _], RocksdbCFOptions],
    readOnly: Boolean,
    startWithBulkInserts: Boolean,
    ioDispatcher: String
  )(implicit akkaEnv: AkkaEnv): RocksdbDb[DbDef] = {
    new RocksdbDb[DbDef](definition, path, options, readOnly, startWithBulkInserts, ioDispatcher)
  }
}

final class RocksdbDb[DbDef <: DbDefinition](
  val definition: DbDef,
  path: String,
  options: Map[DbDef#BaseCol[_, _], RocksdbCFOptions],
  readOnly: Boolean,
  startWithBulkInserts: Boolean,
  ioDispatcher: String
)(implicit akkaEnv: AkkaEnv)
    extends DbInterface[DbDef]
    with StrictLogging {

  import RocksdbDb._

  type AnyCol = DbDef#BaseCol[_, _]

  val isLocal: Boolean = true

  private val columnOptions: Map[AnyCol, ColumnFamilyOptions] = definition.columns.values.map { c =>
    val col = c.asInstanceOf[AnyCol]
    val defaultOptions = col.rocksdbOptions
    val o: RocksdbCFOptions = options.getOrElse(col, defaultOptions)

    val b = RocksdbCFBuilder(o.memoryBudget, o.blockCache)
    val b1 =
      if (defaultOptions.minPrefixLength > 0) b.withCappedPrefixExtractor(defaultOptions.minPrefixLength)
      else b.withPointLookup()

    (col, b1.build())
  }.toMap

  private lazy val dbOptions: DBOptions = {
    val options = new DBOptions()

    val totalWriteBufferSize = columnOptions.values.map(_.writeBufferSize()).sum

    options
      .setMaxBackgroundCompactions(Runtime.getRuntime.availableProcessors() / 2)
      .setMaxOpenFiles(-1)
      .setKeepLogFileNum(3)
      .setMaxTotalWalSize(totalWriteBufferSize * 8)
  }

  protected def logPath: Option[String] = None

  private def columnFamilyWithName(name: String): Option[AnyCol] =
    definition.columns.withNameOption(name).asInstanceOf[Option[AnyCol]]

  private def newReadOptions(): ReadOptions = {
    // ZFS already takes care of checksumming
    new ReadOptions().setVerifyChecksums(false)
  }

  private def syncColumnFamilies(descriptors: List[ColumnFamilyDescriptor], existingColumnNames: Set[String]): Unit = {
    if (existingColumnNames.isEmpty) {
      logger.info("Opening database for the first time, creating column families...")
      val nonDefaultColumns = descriptors
        .filterNot(_.getName.sameElements(RocksDB.DEFAULT_COLUMN_FAMILY))

      if (nonDefaultColumns.nonEmpty) {
        val db = RocksDB.open(new Options().setCreateIfMissing(true), path)

        val columns = nonDefaultColumns.map { d =>
          db.createColumnFamily(d)
        }

        columns.foreach(_.close())
        db.close()
      }
    }
    else {
      val handles = new JavaArrayList[ColumnFamilyHandle]
      val existingDescriptor = descriptors.filter(d => existingColumnNames.contains(byteArrayToString(d.getName)))
      val toCreateDescriptors = descriptors.filter(d => !existingColumnNames.contains(byteArrayToString(d.getName)))

      val db = RocksDB.open(new DBOptions(), path, existingDescriptor.asJava, handles)

      val newHandles = toCreateDescriptors.map { d =>
        logger.info(s"Creating column family: ${byteArrayToString(d.getName)}")
        db.createColumnFamily(d)
      }

      handles.asScala.foreach(_.close())
      newHandles.foreach(_.close())
      db.close()
    }
  }

  private def listExistingColumnNames(): List[String] = {
    RocksDB.listColumnFamilies(new Options(), path).asScala.map(byteArrayToString).toList
  }

//  private val bulkInsertsLock = MVar.of[Task, Boolean](startWithBulkInserts).memoize

  private lazy val ioEc = akkaEnv.actorSystem.dispatchers.lookup(ioDispatcher)
  private lazy val ioZioExecutor = Executor.fromExecutionContext(Int.MaxValue)(ioEc)
  private lazy val blockingEnv = new Blocking {
    val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
      val blockingExecutor: ZIO[Any, Nothing, Executor] = UIO.succeed(ioZioExecutor)
    }
  }

  private val dbCloseSignal = new DbCloseSignal

  private lazy val _references = {
    val task = blocking(Task {
      RocksDB.loadLibrary()
      val columnNames = columnOptions.keys.map(_.entryName).toSet
      val enumNameSet = definition.columns.values.map(_.entryName).toSet

      assert(
        columnNames == enumNameSet,
        s"DbDef#BaseCol enum set: $columnNames differs from column options columnOptionsAsLid map: $enumNameSet"
      )

      val columnOptionsAsList = columnOptions.toList
      val descriptors = columnOptionsAsList.map {
        case (columnFamilyName, colOptions) =>
          new ColumnFamilyDescriptor(
            columnFamilyName.entryName.getBytes(UTF_8),
            colOptions.setDisableAutoCompactions(startWithBulkInserts)
          )
      }

      val exists = File(path + "/CURRENT").exists

      if (!exists && readOnly)
        throw InvalidDbArgumentException(s"Opening database at $path as readyOnly but it doesn't exist")

      def openDb() = {
        val handles = new JavaArrayList[ColumnFamilyHandle]

        val _ = dbOptions
          .setStatsDumpPeriodSec(60)
          .setStatistics(new Statistics())

        val db = {
          if (readOnly) RocksDB.openReadOnly(dbOptions, path, descriptors.asJava, handles)
          else RocksDB.open(dbOptions.setCreateIfMissing(true), path, descriptors.asJava, handles)
        }

        val columnHandles = handles.asScala.toList

        val columnHandleMap = columnOptionsAsList.map(_._1).zip(columnHandles).toMap

        (db, columnHandleMap, dbOptions.statistics())
      }

      if (exists) {
        val existingColumnNames = listExistingColumnNames().toSet

        if (columnNames != existingColumnNames) {
          syncColumnFamilies(descriptors, existingColumnNames)

          val doubleCheckingColumnNames = listExistingColumnNames().toSet
          assert(
            columnNames == doubleCheckingColumnNames,
            s"Trying to open with $columnNames but existing columns are $doubleCheckingColumnNames"
          )
        }
        else {
          assert(
            columnNames == existingColumnNames,
            s"Trying to open with $columnNames but existing columns are $existingColumnNames"
          )
        }
      }
      else {
        syncColumnFamilies(descriptors, Set.empty[String])
      }

      openDb()
    }).flatMap {
      case (db, columnHandleMap, stats) =>
        readColumnFamilyOptionsFromDisk(path)
          .map { r =>
            val columnHasPrefixExtractorMap: Map[AnyCol, String] = r.map {
              case (colName, colMap) =>
                val prefixExtractor = colMap("prefix_extractor")
                (columnFamilyWithName(colName).get, prefixExtractor)
            }.toMap
            DbReferences[DbDef#BaseCol](db, columnHandleMap, columnHasPrefixExtractorMap, stats)
          }
    }

    akkaEnv.unsafeRunToFuture(task.provide(blockingEnv))
  }

  private def ioTask[T](task: Task[T]): Task[T] = {
    task.lock(ioZioExecutor)
  }

  private val isClosed = new AtomicBoolean(false)

  private val DbClosedException = DbAlreadyClosedException("Database was already closed")

//  private val memoizedReferencesTask: ZIO[Any, Nothing, IO[Throwable, DbReferences[DbDef#BaseCol]]] = Task.fromFuture(_ => _references).memoize

  def references: Task[DbReferences[DbDef#BaseCol]] = Task(isClosed.get).flatMap { isClosed =>
    if (isClosed) Task.fail(DbClosedException)
    else {
      _references.value.fold(Task.fromFuture(_ => _references))(Task.fromTry(_))
    }
//    else if (_references.isCompleted) Task.fromTry(_references.value.get)
//    else Task.fromFuture(_ => _references)
  }

  def openTask(): Task[Unit] = references.map(_ => ())

  def compactTask(): Task[Unit] = references.flatMap { refs =>
    ioTask(Task {
      refs.columnHandleMap.values.foreach { col =>
        refs.db.compactRange(col)
      }
    })
  }

  def typedStatsTask(): Task[RocksdbLocalDbStats] = references.map { refs =>
    val stats = refs.stats
    val tickers = TickerType
      .values()
      .filter(_ != TickerType.TICKER_ENUM_MAX)
      .map { t =>
        (t, stats.getTickerCount(t))
      }
      .toMap

    val histograms = HistogramType
      .values()
      .filter(_ != HistogramType.HISTOGRAM_ENUM_MAX)
      .map { h =>
        val hist = stats.getHistogramData(h)
        (
          h,
          RocksdbLocalDbHistogram(
            median = hist.getMedian,
            p95 = hist.getPercentile95,
            p99 = hist.getPercentile99,
            average = hist.getAverage,
            stdDev = hist.getStandardDeviation
          )
        )
      }
      .toMap

    val activeMemTables = refs.db.getProperty("rocksdb.cur-size-active-mem-table").toLong
    val unflushedMemTables = refs.db.getProperty("rocksdb.cur-size-all-mem-tables").toLong - activeMemTables
    val pinnedMemTables = refs.db
      .getProperty("rocksdb.size-all-mem-tables")
      .toLong - activeMemTables - unflushedMemTables
    val tableReadersMem = refs.db.getProperty("rocksdb.estimate-table-readers-mem").toLong
    val numEntriesActiveMemTable = refs.db.getProperty("rocksdb.num-entries-active-mem-table").toLong
    val estimateTableReadersMem = refs.db.getProperty("rocksdb.estimate-table-readers-mem").toLong
    val estimateLiveDataSize = refs.db.getProperty("rocksdb.estimate-live-data-size").toLong

    val memoryUsage = Map[MemoryUsageType, Long](
      MemoryUsageType.ActiveMemTables -> activeMemTables,
      MemoryUsageType.NumEntriesActiveMemTable -> numEntriesActiveMemTable,
      MemoryUsageType.UnflushedMemTables -> unflushedMemTables,
      MemoryUsageType.PinnedMemTables -> pinnedMemTables,
      MemoryUsageType.TableReaders -> tableReadersMem,
      MemoryUsageType.EstimateTableReadersMem -> estimateTableReadersMem,
      MemoryUsageType.EstimateLiveDataSize -> estimateLiveDataSize
    )

    RocksdbLocalDbStats(tickers, histograms, memoryUsage)
  }

  def statsTask: Task[Map[String, Double]] = {
    typedStatsTask().map { stats =>
      val tickers = stats.tickers.map {
        case (k, v) =>
          ("rocksdb_ticker_" + k.toString.toLowerCase, v.toDouble)
      }

      val p99Histograms = stats.histograms.map {
        case (k, v) =>
          ("rocksdb_p99_" + k.toString.toLowerCase, v.p99)
      }

      val memoryUsage = stats.memoryUsage.map {
        case (k, v) =>
          ("rocksdb_mem_" + k.entryName, v.toDouble)
      }

      tickers ++ p99Histograms ++ memoryUsage
    }
  }

  private val InvalidIterator = Left(Array.emptyByteArray)

  private def doGetTask(iter: RocksIterator, constraints: List[DbKeyConstraint]): Either[Array[Byte], DbPair] = {
    if (constraints.isEmpty) {
      Left(Array.emptyByteArray)
    }
    else {
      val headConstraint = constraints.head
      val tailConstraints = constraints.tail

      val headOperand = headConstraint.operand.toByteArray
      val operator = headConstraint.operator

      operator match {
        case Operator.FIRST | Operator.LAST =>
          if (operator.isFirst) iter.seekToFirst() else iter.seekToLast()

          if (iter.isValid) {
            val key = iter.key()
            if (keySatisfies(key, tailConstraints)) Right(key -> iter.value())
            else Left(key)
          }
          else InvalidIterator

        case Operator.EQUAL =>
          iter.seek(headOperand)

          if (iter.isValid) {
            val key = iter.key()
            //noinspection CorrespondsUnsorted
            if (DbKey.isEqual(key, headOperand) && keySatisfies(key, tailConstraints)) Right(key -> iter.value())
            else Left(key)
          }
          else InvalidIterator

        case Operator.LESS_EQUAL | Operator.GREATER_EQUAL =>
          if (operator.isLessEqual) iter.seekForPrev(headOperand) else iter.seek(headOperand)

          if (iter.isValid) {
            val key = iter.key()
            if (keySatisfies(key, tailConstraints)) Right(key -> iter.value())
            else Left(key)
          }
          else InvalidIterator

        case Operator.LESS | Operator.GREATER =>
          if (operator.isLess) iter.seekForPrev(headOperand) else iter.seek(headOperand)

          if (iter.isValid) {
            val key = iter.key()

            if (DbKey.isEqual(key, headOperand)) {
              if (operator.isLess) iter.prev() else iter.next()

              if (iter.isValid) {
                val newKey = iter.key()
                if (keySatisfies(newKey, tailConstraints)) Right(newKey -> iter.value())
                else Left(newKey)
              }
              else Left(key)
            }
            else {
              if (keySatisfies(key, tailConstraints)) Right(key -> iter.value())
              else Left(key)
            }
          }
          else InvalidIterator

        case Operator.PREFIX =>
          iter.seek(headOperand)

          if (iter.isValid) {
            val key = iter.key()
            if (DbKey.isPrefix(headOperand, key) && keySatisfies(key, tailConstraints)) Right(key -> iter.value())
            else Left(key)
          }
          else InvalidIterator

        case Operator.Unrecognized(value) =>
          throw new IllegalArgumentException(s"""Got Operator.Unrecognized($value)""")
      }
    }
  }

  private def constraintsNeedTotalOrder(constraints: List[DbKeyConstraint]): Boolean = {
    constraints.headOption.exists {
      _.operator match {
        case Operator.LESS_EQUAL | Operator.LESS => true
        case _ => false
      }
    }
  }

  def getTask[Col <: DbDef#BaseCol[_, _]](column: Col, constraintList: DbKeyConstraintList): Task[Option[DbPair]] = {
    ioTask(references.map { refs =>
      val db = refs.db
      val columnHandle = refs.getColumnHandle(column)
      val options = newReadOptions()
      val constraints = constraintList.constraints
      if (constraintsNeedTotalOrder(constraints)) {
        val _ = options.setTotalOrderSeek(true)
      }
      val iter = db.newIterator(columnHandle, options)

      try {
        doGetTask(iter, constraints).toOption
      } finally {
        options.close()
        iter.close()
      }
    })
  }

  def batchGetTask[Col <: DbDef#BaseCol[_, _]](
    column: Col,
    requests: Seq[DbKeyConstraintList]
  ): Task[Seq[Option[DbPair]]] = {
    ioTask(references.map { refs =>
      val db = refs.db
      val columnHandle = refs.getColumnHandle(column)
      val options = newReadOptions()
      if (requests.exists(r => constraintsNeedTotalOrder(r.constraints))) {
        val _ = options.setTotalOrderSeek(true)
      }
      val iter = db.newIterator(columnHandle, options)

      try {
        requests.map(r => doGetTask(iter, r.constraints).toOption)
      } finally {
        options.close()
        iter.close()
      }
    })
  }

  def estimateCount[Col <: DbDef#BaseCol[_, _]](column: Col): Task[Long] = {
    ioTask(references.map { refs =>
      val columnHandle = refs.getColumnHandle(column)
      refs.db.getProperty(columnHandle, ESTIMATE_NUM_KEYS).toLong
    })
  }

  private def doPut(db: RocksDB, columnHandle: ColumnFamilyHandle, key: Array[Byte], value: Array[Byte]): Unit = {
    db.put(columnHandle, key, value)
  }

  def putTask[Col <: DbDef#BaseCol[_, _]](column: Col, key: Array[Byte], value: Array[Byte]): Task[Unit] = {
    ioTask(references.map { refs =>
      doPut(refs.db, refs.getColumnHandle(column), key, value)
    })
  }

  private def doDelete(db: RocksDB, columnHandle: ColumnFamilyHandle, key: Array[Byte]): Unit = {
    db.delete(columnHandle, key)
  }

  def deleteTask[Col <: DbDef#BaseCol[_, _]](column: Col, key: Array[Byte]): Task[Unit] = {
    ioTask(references.map { refs =>
      doDelete(refs.db, refs.getColumnHandle(column), key)
    })
  }

  private def determineDeletePrefixRange(
    db: RocksDB,
    columnHandle: ColumnFamilyHandle,
    prefix: Array[Byte]
  ): (Long, Option[Array[Byte]], Option[Array[Byte]]) = {
    val iter = db.newIterator(columnHandle)

    try {
      iter.seek(prefix)

      if (iter.isValid) {
        val firstKey = iter.key

        if (DbKey.isPrefix(prefix, firstKey)) {
          val (count, lastKey) = Iterator
            .continually {
              iter.next()
              iter.isValid
            }
            .takeWhile(identity)
            .map(_ => iter.key)
            .takeWhile(DbKey.isPrefix(prefix, _))
            .foldLeft((1L, firstKey)) {
              case ((c, _), k) =>
                (c + 1, k)
            }

          (count, Some(firstKey), Some(lastKey))
        }
        else (0L, None, None)
      }
      else (0L, None, None)
    } finally {
      iter.close()
    }
  }

  def deletePrefixTask[Col <: DbDef#BaseCol[_, _]](column: Col, prefix: Array[Byte]): Task[Long] = {
    ioTask(references.map { refs =>
      val columnHandle = refs.getColumnHandle(column)
      val db = refs.db
      val writeBatch = new WriteBatch()

      val count = doDeletePrefix(db, columnHandle, writeBatch, prefix)

      try {
        if (count > 0) {
          val writeOptions = new WriteOptions()

          try {
            db.write(writeOptions, writeBatch)
          } finally {
            writeOptions.close()
          }
        }
        count
      } finally {
        writeBatch.close()
      }
    })
  }

  private def doDeletePrefix(
    db: RocksDB,
    columnHandle: ColumnFamilyHandle,
    writeBatch: WriteBatch,
    prefix: Array[Byte]
  ): Long = {
    determineDeletePrefixRange(db, columnHandle, prefix) match {
      case (c, Some(firstKey), Some(lastKey)) =>
        if (c > 1) {
          writeBatch.deleteRange(columnHandle, firstKey, lastKey)
        }
        writeBatch.delete(columnHandle, lastKey)
        c
      case _ =>
        0L
    }
  }

  def iterateValuesSource[Col <: DbDef#BaseCol[_, _]](column: Col, range: DbKeyRange)(
    implicit clientOptions: DbClientOptions
  ): Source[DbValueBatch, Future[NotUsed]] = {
    iterateSource(column, range)
      .map(_.map(_._2))
  }

  def iterateSource[Col <: DbDef#BaseCol[_, _]](column: Col, range: DbKeyRange)(
    implicit clientOptions: DbClientOptions
  ): Source[DbBatch, Future[NotUsed]] = {
    Source
      .lazilyAsync(() => {
        val task = references
          .map {
            refs =>
              val init = () => {
                val columnHandle = refs.getColumnHandle(column)
                val db = refs.db
                val readOptions = newReadOptions()
                val fromConstraints = range.from
                val toConstraints = range.to

                if (constraintsNeedTotalOrder(fromConstraints)) {
                  val _ = readOptions.setTotalOrderSeek(true)
                }
                val iter = db.newIterator(columnHandle, readOptions)

                val close = () => {
                  readOptions.close()
                  iter.close()
                }

                doGetTask(iter, fromConstraints) match {
                  case Right(p) if keySatisfies(p._1, toConstraints) =>
                    val it = Iterator(p) ++ Iterator
                      .continually {
                        iter.next()
                        // scalastyle:off null
                        if (iter.isValid) iter.key() else null
                        // scalastyle:on null
                      }
                      .takeWhile(key => key != null && keySatisfies(key, toConstraints))
                      .map(key => key -> iter.value())

                    Right(DbIterateSourceGraphRefs(it, close))

                  case Right((k, _)) =>
                    close()
                    val message = column
                      .decodeKey(k)
                      .map { d =>
                        s"Starting key: [$d] satisfies fromConstraints [${fromConstraints.show}] but does not satisfy toConstraint: [${toConstraints.show}]"
                      }
                      .getOrElse(
                        s"Failed decoding key with bytes: ${k.mkString(",")}. Constraints: [${fromConstraints.show}]"
                      )

                    Left(SeekFailure(message))

                  case Left(k) =>
                    close()
                    val message = {
                      if (k.nonEmpty) {
                        column
                          .decodeKey(k)
                          .map { d =>
                            s"Starting key: [$d] does not satisfy constraints: [${fromConstraints.show}]"
                          }
                          .getOrElse(
                            s"Failed decoding key with bytes: [${k.mkString(",")}]. Constraints: [${fromConstraints.show}]"
                          )
                      }
                      else s"There's no starting key satisfying constraints: [${fromConstraints.show}]"
                    }

                    Left(SeekFailure(message))
                }
              }

              Source
                .fromGraph(new DbIterateSourceGraph(init, dbCloseSignal, ioDispatcher))
          }

        akkaEnv.unsafeRunToFuture(task)
      })
      .flatMapConcat(identity)
      .addAttributes(Attributes.inputBuffer(1, 1))
  }

  def closeTask(): TaskR[Blocking with Clock, Unit] = {
    import scalaz.zio.duration._

    for {
      refs <- references
      _ <- Task(isClosed.compareAndSet(false, true)).flatMap { isClosed =>
        if (isClosed) Task.unit
        else Task.fail(DbClosedException)
      }
      _ <- Task(dbCloseSignal.tryComplete(Failure(DbClosedException)))
      _ <- Task(dbCloseSignal.hasNoListeners)
        .repeat(ZSchedule.fixed(100.millis).untilInput[Boolean](identity))
      _ <- blocking(Task {
        val DbReferences(db, columnHandleMap, _, stats) = refs
        stats.close()
        columnHandleMap.values.foreach { c =>
          db.flush(new FlushOptions().setWaitForFlush(true), c)
        }
        columnHandleMap.foreach(_._2.close())
        db.close()
      })
    } yield ()
  }

  private def createTailSource[Col <: DbDef#BaseCol[_, _]](
    refs: DbReferences[DbDef#BaseCol],
    column: Col,
    range: DbKeyRange
  )(implicit clientOptions: DbClientOptions): Source[DbTailBatch, NotUsed] = {
    val init = () => {
      val columnHandle = refs.getColumnHandle(column)
      val db = refs.db

      val fromConstraints = range.from
      val toConstraints = range.to

      // scalastyle:off null
      var lastKey: Array[Byte] = null
      var readOptions: ReadOptions = null
      var iter: RocksIterator = null

      val close = () => {
        if (readOptions ne null) {
          readOptions.close()
          readOptions = null
        }
        if (iter ne null) {
          iter.close()
          iter = null
        }
      }
      // scalastyle:on null

      val createIterator = () => {
        readOptions = newReadOptions()
        iter = db.newIterator(columnHandle, readOptions)

        val headConstraints = {
          if (lastKey == null) fromConstraints
          else DbKeyConstraint(Operator.GREATER, ProtoByteString.copyFrom(lastKey)) :: toConstraints
        }

        val head = doGetTask(iter, headConstraints) match {
          case Right(v) if keySatisfies(v._1, toConstraints) => Iterator.single(v)
          case _ => Iterator.empty
        }

        if (head.nonEmpty) {
          val tail = Iterator
            .continually {
              iter.next()
              // scalastyle:off null
              if (iter.isValid) iter.key() else null
              // scalastyle:on null
            }
            .takeWhile(key => key != null && keySatisfies(key, toConstraints))
            .map(key => key -> iter.value())

          (head ++ tail).map { p =>
            lastKey = p._1 // Side-effecting
            p
          }
        }
        else head
      }

      DbTailSourceGraphRefs(createIterator, clear = close, close = close)
    }

    Source
      .fromGraph(new DbTailSourceGraph(init, dbCloseSignal, ioDispatcher))
  }

  def batchTailSource[Col <: DbDef#BaseCol[_, _]](
    column: Col,
    ranges: List[DbKeyRange]
  )(
    implicit clientOptions: DbClientOptions
  ): Source[DbIndexedTailBatch, Future[NotUsed]] = {
    Source
      .lazilyAsync(() => {
        val task = references
          .map {
            refs =>
              if (ranges.exists(_.from.isEmpty)) {
                Source.failed(
                  UnsupportedDbOperationException(
                    "range.from cannot be empty for tailSource, since it can never be satisfied"
                  )
                )
              }
              else {
                def tail(index: Int, range: DbKeyRange) = {
                  createTailSource(refs, column, range)
                    .map(b => (index, b))
                }

                ranges match {
                  case Nil =>
                    Source.failed(UnsupportedDbOperationException("ranges cannot be empty"))

                  case head :: Nil =>
                    tail(0, head)

                  case head :: next :: rest =>
                    Source.combine(
                      tail(0, head),
                      tail(1, next),
                      rest.zipWithIndex.map { case (r, i) => tail(i + 2, r) }: _*
                    )(Merge(_))
                }
              }
          }
        akkaEnv.unsafeRunToFuture(task)
      })
      .flatMapConcat(identity)
      .addAttributes(Attributes.inputBuffer(1, 1))
  }

  def tailSource[Col <: DbDef#BaseCol[_, _]](column: Col, range: DbKeyRange)(
    implicit clientOptions: DbClientOptions
  ): Source[DbTailBatch, Future[NotUsed]] = {

    Source
      .lazilyAsync(() => {
        val task = references
          .map { refs =>
            if (range.from.isEmpty) {
              Source.failed(
                UnsupportedDbOperationException(
                  "range.from cannot be empty for tailSource, since it can never be satisfied"
                )
              )
            }
            else {
              createTailSource(refs, column, range)
            }
          }

        akkaEnv.unsafeRunToFuture(task)
      })
      .flatMapConcat(identity)
  }

  def tailValuesSource[Col <: DbDef#BaseCol[_, _]](
    column: Col,
    range: DbKeyRange
  )(implicit clientOptions: DbClientOptions): Source[DbTailValueBatch, Future[NotUsed]] = {
    tailSource(column, range)
      .map(_.map(_.map(_._2)))
  }

  @SuppressWarnings(Array("org.wartremover.warts.AnyVal"))
  def transactionTask(actions: Seq[DbTransactionAction]): Task[Unit] = {
    ioTask(references.map { refs =>
      val db = refs.db
      val writeBatch = new WriteBatch()

      try {
        for (action <- actions) {
          action.action match {
            case DbTransactionAction.Action.Put(DbPutRequest(columnId, key, value)) =>
              writeBatch.put(refs.getColumnHandle(columnFamilyWithId(columnId).get), key.toByteArray, value.toByteArray)

            case DbTransactionAction.Action.Delete(DbDeleteRequest(columnId, key)) =>
              writeBatch.delete(refs.getColumnHandle(columnFamilyWithId(columnId).get), key.toByteArray)

            case DbTransactionAction.Action.DeletePrefix(DbDeletePrefixRequest(columnId, prefix)) =>
              val column = columnFamilyWithId(columnId).get
              val prefixBytes = prefix.toByteArray

              refs.validateColumnPrefixSeekOperation(column, prefixBytes) match {
                case Some(ex) => throw ex
                case None =>
                  doDeletePrefix(db, refs.getColumnHandle(column), writeBatch, prefixBytes)
              }

            case _ =>
              throw new IllegalArgumentException(s"Invalid transaction action: ${action.action}")
          }
        }

        val writeOptions = new WriteOptions()

        try {
          db.write(writeOptions, writeBatch)
        } finally {
          writeOptions.close()
        }
      } finally {
        writeBatch.close()
      }
    })
  }

  def startBulkInsertsTask(): Task[Unit] = ???

  def endBulkInsertsTask(): Task[Unit] = ???

  def dropColumnFamily[Col <: DbDef#BaseCol[_, _]](column: Col): Task[Unit] = {
    ioTask(references.map { refs =>
      logger.info(s"Dropping column family: ${column.id}")
      refs.db.dropColumnFamily(refs.getColumnHandle(column))
    })
  }
}

package dev.chopsticks.kvdb.rocksdb

import dev.chopsticks.fp.AkkaApp
import dev.chopsticks.kvdb.TestDatabase._
import dev.chopsticks.kvdb.codec.primitive._
import dev.chopsticks.kvdb.rocksdb.RocksdbColumnFamilyConfig.{PointLookupPattern, PrefixedScanPattern}
import dev.chopsticks.kvdb.util.KvdbTestUtils
import dev.chopsticks.kvdb.{ColumnFamilySet, KvdbDatabase, KvdbDatabaseTest}
import eu.timepit.refined.auto._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import org.rocksdb.CompressionType
import squants.information.InformationConversions._
import zio.ZManaged

object RocksdbDatabaseTest {
  object dbMaterialization extends Materialization with RocksdbMaterialization[BaseCf, CfSet] {
    object default extends DefaultCf {
      override lazy val id: String = "default"
    }
    object lookup extends LookupCf

    val columnFamilyConfigMap: RocksdbColumnFamilyConfigMap[BaseCf, CfSet] = {
      RocksdbColumnFamilyConfigMap[BaseCf] of (
        default,
        RocksdbColumnFamilyConfig(
          memoryBudget = 1.mib,
          blockCache = 1.mib,
          blockSize = 8.kib,
          readPattern = PrefixedScanPattern(1),
          compression = CompressionType.NO_COMPRESSION
        )
      ) and (
        lookup,
        RocksdbColumnFamilyConfig(
          memoryBudget = 1.mib,
          blockCache = 1.mib,
          blockSize = 8.kib,
          readPattern = PointLookupPattern,
          compression = CompressionType.NO_COMPRESSION
        )
      )
    }
    val columnFamilySet: ColumnFamilySet[BaseCf, CfSet] = ColumnFamilySet[BaseCf] of default and lookup
  }

  val managedDb: ZManaged[AkkaApp.Env, Throwable, Db] = {
    for {
      dir <- KvdbTestUtils.managedTempDir
      db <- KvdbDatabase.manage {
        RocksdbDatabase(
          dbMaterialization,
          RocksdbDatabase.Config(
            path = refineV[NonEmpty](dir.pathAsString).right.get,
            readOnly = false,
            startWithBulkInserts = false,
            checksumOnRead = true,
            syncWriteBatch = true,
            useDirectIo = true,
            ioDispatcher = "dev.chopsticks.kvdb.test-db-io-dispatcher"
          )
        )
      }
    } yield db
  }
}

final class RocksdbDatabaseTest extends KvdbDatabaseTest {
  protected val dbMat = RocksdbDatabaseTest.dbMaterialization
  protected val managedDb = RocksdbDatabaseTest.managedDb
}

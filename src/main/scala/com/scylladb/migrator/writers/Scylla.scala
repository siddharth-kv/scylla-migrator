package com.scylladb.migrator.writers

import com.datastax.spark.connector.writer._
import com.datastax.spark.connector._
import com.scylladb.migrator.Connectors
import com.scylladb.migrator.config.{ Rename, TargetSettings }
import com.scylladb.migrator.readers.TimestampColumns
import org.apache.log4j.{ LogManager, Logger }
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{ DataFrame, Row, SparkSession }

object Scylla {
  val log: Logger = LogManager.getLogger("com.scylladb.migrator.writer.Scylla")

  def writeDataframe(
    target: TargetSettings.Scylla,
    renames: List[Rename],
    skipColumns: List[String],
    df: DataFrame,
    timestampColumns: Option[TimestampColumns],
    tokenRangeAccumulator: Option[TokenRangeAccumulator])(implicit spark: SparkSession): Unit = {
    val connector = Connectors.targetConnector(spark.sparkContext.getConf, target)
    val tempWriteConf = WriteConf
      .fromSparkConf(spark.sparkContext.getConf)

    val writeConf = {
      if (timestampColumns.nonEmpty) {
        tempWriteConf.copy(
          ttl = timestampColumns.map(_.ttl).fold(TTLOption.defaultValue)(TTLOption.perRow),
          timestamp = timestampColumns
            .map(_.writeTime)
            .fold(TimestampOption.defaultValue)(TimestampOption.perRow)
        )
      } else if (target.writeTTLInS.nonEmpty || target.writeWritetimestampInuS.nonEmpty) {
        var hardcodedTempWriteConf = tempWriteConf
        if (target.writeTTLInS.nonEmpty) {
          hardcodedTempWriteConf =
            hardcodedTempWriteConf.copy(ttl = TTLOption.constant(target.writeTTLInS.get))
        }
        if (target.writeWritetimestampInuS.nonEmpty) {
          hardcodedTempWriteConf = hardcodedTempWriteConf.copy(
            timestamp = TimestampOption.constant(target.writeWritetimestampInuS.get))
        }
        hardcodedTempWriteConf
      } else {
        tempWriteConf
      }
    }

    // Similarly to createDataFrame, when using withColumnRenamed, Spark tries
    // to re-encode the dataset. Instead we just use the modified schema from this
    // DataFrame; the access to the rows is positional anyway and the field names
    // are only used to construct the columns part of the INSERT statement.
    val finalSchema = renames
      .foldLeft(df.drop(skipColumns: _*)) {
        case (acc, Rename(from, to)) => acc.withColumnRenamed(from, to)
      }
      .schema

    log.info("Final schema after column renaming and skipping:")
    log.info(finalSchema.treeString)

    val columnSelector = SomeColumns(finalSchema.fields.map(_.name: ColumnRef): _*)

    // Spark's conversion from its internal Decimal type to java.math.BigDecimal
    // pads the resulting value with trailing zeros corresponding to the scale of the
    // Decimal type. Some users don't like this so we conditionally strip those.
    val rdd =
      if (!target.stripTrailingZerosForDecimals)
        df.select(columnSelector.columns.map(c => col(c.columnName)): _*).rdd
      else
        df.select(columnSelector.columns.map(c => col(c.columnName)): _*).rdd.map { row =>
          Row.fromSeq(row.toSeq.map {
            case x: java.math.BigDecimal => x.stripTrailingZeros()
            case x                       => x
          })
        }

    log.info("Printing example row.." + rdd.take(1).head)

    rdd
      .filter { _.getAs[String]("xuid") != null }
      .saveToCassandra(
        target.keyspace,
        target.table,
        columnSelector,
        writeConf,
        tokenRangeAccumulator = tokenRangeAccumulator
      )(connector, SqlRowWriter.Factory)

    log.info("Writes completed..")
  }

}

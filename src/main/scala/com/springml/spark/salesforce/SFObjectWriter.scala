package com.springml.spark.salesforce

import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SaveMode}
import com.springml.salesforce.wave.api.APIFactory
import com.springml.salesforce.wave.api.BulkAPI
import com.springml.salesforce.wave.util.WaveAPIConstants
import com.springml.salesforce.wave.model.JobInfo

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.util.Try


/**
 * Write class responsible for update Salesforce object using data provided in dataframe
 * First column of dataframe contains Salesforce Object
 * Next subsequent columns are fields to be updated
 */
class SFObjectWriter(
                      val username: String,
                      val password: String,
                      val login: String,
                      val version: String,
                      val sfObject: String,
                      val mode: SaveMode,
                      val upsert: Boolean,
                      val externalIdFieldName: String,
                      val csvHeader: String,
                      val batchSize: Integer
                    ) extends Serializable {

  @transient val logger = Logger.getLogger(classOf[SFObjectWriter])

  // Single BulkAPI instance reused for all driver-side operations (createJob,
  // closeJob, isCompleted). SFConfig.getPartnerConnection() is lazy, so this
  // performs exactly ONE SOAP login for the entire driver-side lifecycle.
  // Marked @transient because BulkAPI is not serializable — executors must
  // create their own instances via newBulkAPI().
  @transient private lazy val driverBulkAPI: BulkAPI = newBulkAPI()

  // Fresh instance for executor-side operations (addBatch inside
  // mapPartitionsWithIndex). Each partition authenticates once.
  private def newBulkAPI(): BulkAPI = {
    APIFactory.getInstance().bulkAPI(username, password, login, version)
  }

  def writeData(rdd: RDD[Row]): Boolean = {

    val csvRDD = rdd.map { row =>
      val schema = row.schema.fields
      row.toSeq.indices.map(
        index => Utils.cast(row, schema(index).dataType, index)
      ).mkString(",")
    }

    val partitionCnt = (1 + csvRDD.count() / batchSize).toInt
    val partitionedRDD = csvRDD.repartition(partitionCnt)

    val jobInfo = new JobInfo(WaveAPIConstants.STR_CSV, sfObject, operation(mode, upsert))
    jobInfo.setExternalIdFieldName(externalIdFieldName)

    val jobId = driverBulkAPI.createJob(jobInfo).getId

    partitionedRDD.mapPartitionsWithIndex {
      case (index, iterator) => {
        val records = iterator.toArray.mkString("\n")
        var batchInfoId: String = null
        if (records != null && !records.isEmpty()) {
          val data = csvHeader + "\n" + records
          val batchInfo = newBulkAPI().addBatch(jobId, data)
          batchInfoId = batchInfo.getId
        }

        val success = (batchInfoId != null)
        List(success).iterator
      }
    }.reduce((a, b) => a & b)

    driverBulkAPI.closeJob(jobId)
    var i = 1
    while (i < 999999) {
      if (driverBulkAPI.isCompleted(jobId)) {
        logger.info("Job completed")
        return true
      }
      logger.info("Job not completed, waiting...")
      Thread.sleep(200)
      i = i + 1
    }

    print("Returning false...")
    logger.info("Job not completed. Timeout...")
    false
  }

  private def operation(mode: SaveMode, upsert: Boolean): String = {
    if (upsert) {
      "upsert"
    } else if (mode != null && SaveMode.Overwrite.name().equalsIgnoreCase(mode.name())) {
      WaveAPIConstants.STR_UPDATE
    } else if (mode != null && SaveMode.Append.name().equalsIgnoreCase(mode.name())) {
      WaveAPIConstants.STR_INSERT
    } else {
      logger.warn("SaveMode " + mode + " Not supported. Using 'insert' operation")
      WaveAPIConstants.STR_INSERT
    }
  }

}
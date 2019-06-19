/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.palo

import java.io.{BufferedWriter, IOException, OutputStreamWriter}
import java.sql.{SQLException, SQLTimeoutException}

import scala.util.matching.Regex

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FSDataOutputStream, Path}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.{StringType, StructType}

/**
 * Use `LOAD` to load data to Palo by hadoop ETL
 * We need write intermediate contents to tmp file on hdfs
 * Read permissions of the path to store tmp files for `others` is needed
 *
 * Note: always used to load large size data(exceed 1GB), make sure the path has enough space.
 */
private[palo] class PaloDfsLoadTask(
    sparkSession: SparkSession,
    batchId: Long,
    checkpointRoot: String,
    parameters: Map[String, String],
    schema: StructType)
  extends PaloWriteTask(sparkSession, batchId, checkpointRoot, parameters, schema) with Logging {

  // we use the {{Configuration}} to get ugi, and in order to unify the way to
  // get configurations we set "parameters"'s Key/Value pair into {{Configuration}}
  private val conf: Configuration = new Configuration
  parameters.foreach { case (key, value) => conf.set(key, value) }

  private val dataDir = checkAndInit(PaloConfig.PALO_DATA_DIR) // hdfs or afs
  private val loadCmd = checkAndInit(PaloConfig.LOADCMD)
  private val ugi = checkAndInit("hadoop.job.ugi")
  private val broker = checkAndInit(PaloConfig.BROKER_NAME)

  private[palo] val paloTimeout = conf.getInt(PaloConfig.PALO_TIME_OUT, 0)
  private[palo] val maxFilterRatio = conf.getDouble(PaloConfig.MAX_FILTER_RATIO, 0)
  private[palo] val loadDeletFlag = conf.get(PaloConfig.LOAD_DELETE_FLAG, "false")
  private[palo] val is_negative = conf.get(PaloConfig.IS_NEGATIVE, "false").toBoolean
  assert(maxFilterRatio >= 0 && maxFilterRatio <= 1 && paloTimeout >= 0)

  private[palo] var fs: FileSystem = null
  private[palo] var dataPath: Path = null
  private[palo] var outputStream: FSDataOutputStream = null
  private[palo] var writer: BufferedWriter = null

  // init outputStream
  init()

  /**
   * check the key which must be specified
   */
  private def checkAndInit(key: String): String = {
    val value = conf.get(key)
    if (value == null) {
      throw new IllegalArgumentException(s"${key} must be specified!!")
    }
    value
  }

  private def init() {
    try {
      fs = FileSystem.newInstance(conf)
      // dataPath is the path of tmp file to store data genererated by Dataset
      // dataPath = dataDir + label
      //  --dataDir specified by customer
      //  --label generated by PaloWriterTask
      dataPath = new Path(dataDir, label)
      logInfo(s"dataDir:${dataDir}, label:${label}, dataPath:${dataPath}")

      // dataPath may exists for failover, delete the dataPath and create new one firstly
      if (fs.exists(dataPath)) {
        fs.delete(dataPath, true)
        logInfo(s"dataPath:${dataPath} exists, delete it successfully")
      }
      outputStream = fs.create(dataPath)
      writer = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"))

      logInfo(s"init successfully, create a new dataPath:${dataPath} to store contents")
    } catch {
      case e: IOException =>
        logError(s"PaloDfsLoadTask init failed: ${e.getMessage}")
        try {
          if (outputStream != null) {
            outputStream.close
          }
        } catch {
          case e1: IOException =>
          logError(s"close outputStream error when init failed:${e1.getMessage}")
        }
        try {
          if (writer != null) {
            writer.close
          }
        } catch {
           case e1: IOException =>
           logError(s"close BufferedWriter error when init failed:${e1.getMessage}")
        }
        throw e
    }
  }

  override def close(): Unit = {
    super.close
    try {
      if (fs != null) {
        fs.close
      }
    } catch {
      case e: IOException => logError(s"close failed for ${e.getMessage}")
      throw e
    }
  }

  /**
   * TODO: need to check "\n" whether in the end of content?
   */
  override def writeToFile(content: String): Long = {
    writer.write(content, 0, content.length)
    content.getBytes("UTF-8").length
  }

  override def loadFileToPaloTable() {
    // 1. flush content in buffer to dfs
    try {
      writer.flush
      outputStream.hflush
      outputStream.hsync
      outputStream.close
      writer.close
    } catch {
      case e: IOException =>
        logError(s"loadFileToPaloTable fail: ${e.getMessage}")
        throw e
    }

    // 2. load File to Palo by SQL
    logInfo(s"start load file ${dataPath} by sql")
    val ugiArray = ugi.split(",")
    val ugiName = ugiArray(0)
    val ugiPassword = ugiArray(1)
    val afsPattern = "afs://.*".r
    val hdfsPattern = "hdfs://.*".r
    val localPattern = "file://.*".r // for unit test
    val defaultFs = conf.get("fs.defaultFS")
    val isNegative = if (is_negative) "NEGATIVE"; else ""

    val sql = s"""LOAD LABEL ${database}.${label}
      | (DATA INFILE("${defaultFs}${dataPath.toUri.toString}")
      | $isNegative
      | INTO TABLE ${table} ${loadCmd})
      | with BROKER ${broker}
      | ("username"="${ugiName}","password"="${ugiPassword}")
      | PROPERTIES("timeout"="${paloTimeout}", "max_filter_ratio"="${maxFilterRatio}",
      | "load_delete_flag" = "false")""".stripMargin.replaceAll("\n", "")
    logDebug(s"sql is: ${sql}")

    var retryTimes = 0
    while(retryTimes <= queryMaxRetries) {
      try {
        client.getPreparedStatement(sql).execute()
        retryTimes = queryMaxRetries + 1
      } catch {
        case e: SQLTimeoutException =>
          retryTimes += 1
          if (retryTimes == queryMaxRetries + 1) {
            needDelete = false
            logWarning(s"after retry ${queryMaxRetries}, throw exception " +
              s"${e.getMessage}, failed to load label: ${label}, " +
              s"retain file for restore, path:${dataPath.toString}")
          } else {
            logWarning(s"execute ${sql} timeout, retry ${retryTimes} time")
            doWait()
          }
        case e: SQLException =>
          logError(s"execute ${sql} to palo failed for: ${e.getMessage}")
          throw e
      }
    } // end while loop
  }

  override def deleteFile() {
    if (needDelete && fs.exists(dataPath)) {
      try {
        if (fs.delete(dataPath, true)) {
          logInfo(s"delete ${dataPath} successfully")
        } else {
          logWarning(s"delete ${dataPath} failed")
        }
      } catch {
        case e: IOException =>
        logError(s"delete file ${dataPath} failed for: ${e.getMessage}")
        throw e
      }
    }
  }
}

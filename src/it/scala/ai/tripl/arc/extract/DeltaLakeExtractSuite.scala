package ai.tripl.arc

import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import collection.JavaConverters._

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import ai.tripl.arc.api._
import ai.tripl.arc.api.API._
import ai.tripl.arc.config.ArcPipeline
import ai.tripl.arc.util._
import ai.tripl.arc.util.ControlUtils._

class DeltaLakeExtractSuite extends FunSuite with BeforeAndAfter {

  var session: SparkSession = _

  val bucketName = "test"

  // minio seems to need ip address not hostname
  val minioHostPort = "http://minio:9000"
  val minioAccessKey = "AKIAIOSFODNN7EXAMPLE"
  val minioSecretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

  val inputView = "inputView"
  val outputURI = s"s3a://${bucketName}/delta"
  val inputURI = outputURI
  val outputView = "outputView"

  before {
    implicit val spark = SparkSession
                  .builder()
                  .master("local[*]")
                  .config("spark.ui.port", "9999")
                  .config("spark.delta.logStore.class", "org.apache.spark.sql.delta.storage.S3SingleDriverLogStore")
                  .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
                  .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
                  .appName("Spark ETL Test")
                  .getOrCreate()
    spark.sparkContext.setLogLevel("INFO")
    implicit val logger = TestUtils.getLogger()

    // set for deterministic timezone
    spark.conf.set("spark.sql.session.timeZone", "UTC")

    session = spark
  }

  after {
  }

  test("DeltaLakeExtractSuite: batch") {
    implicit val spark = session
    import spark.implicits._
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val dataset = TestUtils.getKnownDataset
    dataset.createOrReplaceTempView(inputView)

    val conf = s"""{
      "stages": [
        {
          "type": "DeltaLakeExtract",
          "name": "try to read some data",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "${inputURI}",
          "outputView": "${outputView}",
          "authentication": {
            "method": "AmazonAccessKey",
            "accessKeyID": "${minioAccessKey}",
            "secretAccessKey": "${minioSecretKey}",
            "endpoint": "${minioHostPort}"
          }
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(err) => fail(err.toString)
      case Right((pipeline, _)) => {
        ARC.run(pipeline)
      }
    }

    // assert(spark.table(outputView).count == 2)
  }

}

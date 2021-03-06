package ai.tripl.arc

import java.net.URI
import java.util.UUID
import java.io.File

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import org.apache.avro.Schema
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import ai.tripl.arc.api._
import ai.tripl.arc.api.API._
import ai.tripl.arc.config._
import ai.tripl.arc.util._

class DeltaLakeMergeLoadSuite extends FunSuite with BeforeAndAfter {

  var session: SparkSession = _
  val inputView = "dataset"

  before {
    implicit val spark = SparkSession
                  .builder()
                  .master("local[*]")
                  .config("spark.ui.port", "9999")
                  .appName("Spark ETL Test")
                  .getOrCreate()
    spark.sparkContext.setLogLevel("INFO")

    // set for deterministic timezone
    spark.conf.set("spark.sql.session.timeZone", "UTC")

    session = spark
    import spark.implicits._
  }

  after {
    session.stop()
  }


  test("DeltaLakeMergeLoad: end-to-end") {
    implicit val spark = session
    import spark.implicits._
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val testUUID = UUID.randomUUID.toString
    val output = s"${FileUtils.getTempDirectoryPath}/${testUUID}"

    // create overlapping datasets with insert, update and delete cases
    Seq((0, "a"),(1, "b"),(2, "c"),(3, "d"),(4, "e")).toDF("key", "value").write.format("delta").mode("overwrite").save(output)
    Seq((2, "c"),(3, "z"),(4, "y"),(5, "f"),(6, "g")).toDF("key", "value").createOrReplaceTempView(inputView)

    val conf = s"""{
      "stages": [
        {
          "type": "DeltaLakeMergeLoad",
          "name": "test",
          "description": "test",
          "environments": [
            "production",
            "test"
          ],
          "outputURI": "${output}",
          "inputView": "${inputView}",
          "partitionBy": ["key"],
          "numPartitions": 5,
          "condition": "source.key = target.key",
          "whenMatchedDeleteFirst": true,
          "whenMatchedDelete": {
            "condition": "source.key = 2"
          },
          "whenMatchedUpdate": {
            "condition": "source.key = 3",
            "values": {
              "key": "source.key",
              "value": "source.value"
            }
          },
          "whenNotMatchedByTargetInsert": {
            "condition": "source.key = 5",
            "values": {
              "key": "source.key",
              "value": "source.value"
            }
          },
          "whenNotMatchedBySourceDelete": {
            "condition": "target.key = 1"
          }
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(err) => fail(err.toString)
      case Right((pipeline, _)) => {
        val df = ARC.run(pipeline)(spark, logger, arcContext).get

        val df0 = spark.read.format("delta").option("versionAsOf", 0).load(output).withColumn("_filename", input_file_name()).orderBy(col("key"))
        val df1 = spark.read.format("delta").option("versionAsOf", 1).load(output).withColumn("_filename", input_file_name()).orderBy(col("key"))
        val join = df0.joinWith(df1, df0("key") === df1("key"), "full")
        val rows = join.collect

        val KEY = 0
        val VALUE = 1
        val FILENAME = 2

        assert(rows.filter { case(v0, v1) => !v0.isNullAt(KEY) && v0.getInt(KEY) == 0 && !v1.isNullAt(KEY) && v1.getInt(KEY) == 0 }.length == 1, "key 0 not deleted as per whenNotMatchedBySourceDelete.condition")
        assert(rows.filter { case(v0, v1) => !v0.isNullAt(KEY) && v0.getInt(KEY) == 1 && v1.isNullAt(KEY) }.length == 1, "key 1 deleted as per whenNotMatchedBySourceDelete.condition")
        assert(rows.filter { case(v0, v1) => !v0.isNullAt(KEY) && v0.getInt(KEY) == 2 && v1.isNullAt(KEY) }.length == 1, "key 2 deleted as per whenMatchedDelete.condition")
        assert(rows.filter { case(v0, v1) => !v0.isNullAt(KEY) && v0.getInt(KEY) == 3 && !v1.isNullAt(KEY) && v1.getString(VALUE) == "z" }.length == 1, "key 3 updated as per whenMatchedUpdate.condition")
        assert(rows.filter { case(v0, v1) => !v0.isNullAt(KEY) && v0.getInt(KEY) == 4 && !v1.isNullAt(KEY) && v0.getString(VALUE) == v1.getString(VALUE)}.length == 1, "key 4 not updated as per whenMatchedUpdate.condition")
        assert(rows.filter { case(v0, v1) => v0.isNullAt(KEY) && !v1.isNullAt(KEY) && v1.getInt(KEY) == 5 }.length == 1, "key 5 inserted as per whenNotMatchedByTargetInsert.condition")
        assert(rows.filter { case(v0, v1) => !v1.isNullAt(KEY) && v1.getInt(KEY) == 6 }.length == 0, "key 6 not inserted as per whenNotMatchedByTargetInsert.condition")
      }
    }
  }

  test("DeltaLakeMergeLoad: createTableIfNotExists = false") {
    implicit val spark = session
    import spark.implicits._
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val testUUID = UUID.randomUUID.toString
    val output = s"${FileUtils.getTempDirectoryPath}/${testUUID}"

    // create a dataset that does not exist on disk
    Seq((2, "c"),(3, "z"),(4, "y"),(5, "f"),(6, "g")).toDF("key", "value").createOrReplaceTempView(inputView)

    val conf = s"""{
      "stages": [
        {
          "type": "DeltaLakeMergeLoad",
          "name": "test",
          "description": "test",
          "environments": [
            "production",
            "test"
          ],
          "outputURI": "${output}",
          "inputView": "${inputView}",
          "createTableIfNotExists": false,
          "partitionBy": ["key"],
          "numPartitions": 5,
          "condition": "source.key = target.key"
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(err) => fail(err.toString)
      case Right((pipeline, _)) => {
        val thrown0 = intercept[Exception with DetailException] {
          val df = ARC.run(pipeline)(spark, logger, arcContext).get
          df.take(1)
        }
        assert(thrown0.getMessage.contains("is not a Delta table and 'createTableIfNotExists' is false so cannot complete this operation"))
      }
    }
  }

  test("DeltaLakeMergeLoad: createTableIfNotExists = true") {
    implicit val spark = session
    import spark.implicits._
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val testUUID = UUID.randomUUID.toString
    val output = s"${FileUtils.getTempDirectoryPath}/${testUUID}"

    // create a dataset that does not exist on disk
    Seq((2, "c"),(3, "z"),(4, "y"),(5, "f"),(6, "g")).toDF("key", "value").createOrReplaceTempView(inputView)

    val conf = s"""{
      "stages": [
        {
          "type": "DeltaLakeMergeLoad",
          "name": "test",
          "description": "test",
          "environments": [
            "production",
            "test"
          ],
          "outputURI": "${output}",
          "inputView": "${inputView}",
          "createTableIfNotExists": true,
          "partitionBy": ["key"],
          "numPartitions": 5,
          "condition": "source.key = target.key"
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(err) => fail(err.toString)
      case Right((pipeline, _)) => {
        val df = ARC.run(pipeline)(spark, logger, arcContext).get
        df.take(1)
      }
    }
  }

}
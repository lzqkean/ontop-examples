import java.io.FileWriter
import java.util.TimeZone
import java.util.concurrent.TimeUnit

import org.apache.spark.sql._
import org.apache.spark.sql.types._
import queries.utils.{CoalesceAlg, Utility}

object ShoweryPatternCounty {

  def main(args: Array[String]): Unit = {

//    var startLoad:Long = 0
//    var endLoad:Long = 0
//    var startRun:Long = 0
//    var endRun:Long = 0

    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    val sparkSession = SparkSession.builder.appName(args(3))
      .master("local[48]")
      .config("spark.eventLog.enabled", true)
      .config("spark.eventLog.dir", args(2))
      .getOrCreate

    sparkSession.conf.set("spark.ui.enabled", true)
    sparkSession.sparkContext.setLogLevel("WARN")

    val customSchema = new StructType(Array[StructField](
      StructField("station_id", DataTypes.StringType, nullable = false, Metadata.empty),
      StructField("date_time", DataTypes.TimestampType, nullable = false, Metadata.empty),
      StructField("air_temp_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("relative_humidity_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("wind_speed_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("wind_gust_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("wind_direction_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("pressure_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("pressure_tendency_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("weather_cond_code_set_1", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("visibility_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("cloud_layer_1_code_set_1", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("air_temp_high_6_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("air_temp_low_6_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("air_temp_high_24_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("air_temp_low_24_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("precip_accum_one_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("precip_accum_three_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("precip_accum_six_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("precip_accum_24_hour_set_1", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("snow_depth_set_1", DataTypes.FloatType, nullable = true, Metadata.empty)
    ))

//    startLoad = java.lang.System.currentTimeMillis()

    var df = sparkSession.read.option("header", "true").schema(customSchema).parquet(args(0) + args(1))

    //df_dry.write.parquet("/Users/user/Desktop/tb_data2012_1gb.parquet")

    //****************** METADATA *******************//

    val customSchema2 = new StructType(Array[StructField](
      StructField("status", DataTypes.StringType, nullable = false, Metadata.empty),
      StructField("mnet_id", DataTypes.IntegerType, nullable = false, Metadata.empty),
      StructField("startdate", DataTypes.TimestampType, nullable = true, Metadata.empty),
      StructField("enddate", DataTypes.TimestampType, nullable = true, Metadata.empty),
      StructField("elevation", DataTypes.IntegerType, nullable = true, Metadata.empty),
      StructField("name", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("shortname", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("sgid", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("nwsfirezone", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("stid", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("longitude", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("county", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("cwa", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("state", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("nwszone", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("latitude", DataTypes.FloatType, nullable = true, Metadata.empty),
      StructField("timezone", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("gacc", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("country", DataTypes.StringType, nullable = true, Metadata.empty),
      StructField("id", DataTypes.IntegerType, nullable = true, Metadata.empty)
    ))

    var df_md_1 = sparkSession.read.option("header", "true").schema(customSchema2).parquet(args(0) + "tb_metadata.parquet")


//    endLoad = java.lang.System.currentTimeMillis()
//    startRun = java.lang.System.currentTimeMillis()

    df.createOrReplaceTempView("tb_weatherdata")
    df_md_1.createOrReplaceTempView("tb_metadata")

    //****************** DIAMOND_RAIN *******************//

    var df_diamond_rain = sparkSession.sql("SELECT SID, dFrom, dTo \n" +
      "FROM ( \n" +
      "SELECT date_time AS dTo, precip_accum_one_hour_set_1 AS currP1, \n" +
      "lag(date_time, 1) OVER (PARTITION BY station_id ORDER BY date_time) AS dFrom, \n" +
      "lag(precip_accum_one_hour_set_1) OVER (PARTITION BY station_id ORDER BY date_time) AS prevP1, \n" +
      "station_id AS SID, air_temp_set_1,\n" +
      "ROW_NUMBER() OVER(PARTITION BY station_id ORDER BY date_time) AS rnm  \n" +
      "FROM tb_weatherdata) as sub \n" +
      "WHERE ((currP1 > prevP1) OR ((currP1 < prevP1) AND (prevP1 > 0))) AND air_temp_set_1 > 5 AND rnm > 1")

    df_diamond_rain = df_diamond_rain.filter(df_diamond_rain.col("dFrom").plus(functions.expr("INTERVAL 1 DAY")).geq(df_diamond_rain.col("dTo")))

    df_diamond_rain = Utility.coalesceWithPartition(sparkSession, df_diamond_rain, "SID", "dFrom", "dto")
    
    df_diamond_rain.createOrReplaceTempView("RAIN")

    df_diamond_rain = df_diamond_rain.select(df_diamond_rain.col("SID"), df_diamond_rain.col("dFrom"), df_diamond_rain.col("dTo").plus(functions.expr("INTERVAL 30 MINUTES")).as("dTo"))

    df_diamond_rain.createOrReplaceTempView("DIAMONDRAIN")
    
    //****************** DRY *******************//

    var df_dry = sparkSession.sql("SELECT SID, dFrom, dTo \n" +
      "FROM ( \n" +
      "SELECT date_time AS dTo, precip_accum_one_hour_set_1 AS currP1, \n" +
      "lag(date_time, 1) OVER (PARTITION BY station_id ORDER BY date_time) AS dFrom,\n" +
      "lag(precip_accum_one_hour_set_1) OVER (PARTITION BY station_id ORDER BY date_time) AS prevP1,\n" +
      "station_id AS SID, air_temp_set_1,\n" +
      "ROW_NUMBER() OVER(PARTITION BY station_id ORDER BY date_time) AS rnm  \n" +
      "FROM tb_weatherdata) as sub \n" +
      "WHERE ((currP1 = prevP1) OR (currP1 = 0)) AND rnm > 1")

    df_dry = df_dry.filter(df_dry.col("dFrom").plus(functions.expr("INTERVAL 1 DAY")).geq(df_dry.col("dTo")))

    df_dry = Utility.coalesceWithPartition(sparkSession, df_dry, "SID", "dFrom", "dto")
    
    df_dry.createOrReplaceTempView("DRY")

    //****************** DIAMONDRAINANDDRY *******************//

    var df_diamond_rain_and_dry = sparkSession.sql("SELECT DIAMONDRAIN.SID AS SID, \n" +
      "CASE \n" +
      "WHEN DIAMONDRAIN.dFrom > DRY.dFrom AND DRY.dTo > DIAMONDRAIN.dFrom THEN DIAMONDRAIN.dFrom \n" +
      "WHEN DRY.dFrom > DIAMONDRAIN.dFrom AND DIAMONDRAIN.dTo > DRY.dFrom THEN DRY.dFrom \n" +
      "WHEN DIAMONDRAIN.dFrom = DRY.dFrom THEN DIAMONDRAIN.dFrom \n" +
      "END AS dFrom, \n" +
      "CASE \n" +
      "WHEN DIAMONDRAIN.dTo < DRY.dTo AND DIAMONDRAIN.dTo > DRY.dFrom THEN DIAMONDRAIN.dTo \n" +
      "WHEN DRY.dTo < DIAMONDRAIN.dTo AND DRY.dTo > DIAMONDRAIN.dFrom THEN DRY.dTo \n" +
      "WHEN DIAMONDRAIN.dTo = DRY.dTo THEN DIAMONDRAIN.dTo \n" +
      "END AS dTo \n" +
      "FROM DIAMONDRAIN, DRY \n" +
      "WHERE DIAMONDRAIN.SID = DRY.SID AND \n" +
      "((DIAMONDRAIN.dFrom > DRY.dFrom AND DRY.dTo > DIAMONDRAIN.dFrom) OR (DRY.dFrom > DIAMONDRAIN.dFrom AND DIAMONDRAIN.dTo > DRY.dFrom) OR (DIAMONDRAIN.dFrom = DRY.dFrom)) AND \n" +
      "((DIAMONDRAIN.dTo < DRY.dTo AND DIAMONDRAIN.dTo > DRY.dFrom) OR (DRY.dTo < DIAMONDRAIN.dTo AND DRY.dTo > DIAMONDRAIN.dFrom) OR (DIAMONDRAIN.dTo = DRY.dTo))")

    df_diamond_rain_and_dry.createOrReplaceTempView("DIAMONDRAINANDDRY")

    scala.util.Try(sparkSession.sqlContext.dropTempTable("DIAMONDRAIN"))
    scala.util.Try(sparkSession.sqlContext.dropTempTable("DRY"))


    //****************** LOCATIONOFRAD*******************//

    var df_location_of_rad = sparkSession.sql("SELECT county, dFrom, dTo FROM DIAMONDRAINANDDRY, tb_metadata WHERE SID = stid ORDER BY (county, dFrom)")

    //scala.util.Try(sparkSession.sqlContext.dropTempTable("DIAMONDRAINANDDRY"))

    df_location_of_rad = Utility.coalesceWithPartition(sparkSession, df_location_of_rad.repartition(df_location_of_rad.col("county")).sortWithinPartitions("state", "dFrom"), "county", "dFrom", "dto")

    df_location_of_rad.createOrReplaceTempView("LOCATIONOFRAD")

    //****************** LOCATIONOFRAIN *******************//

    var df_location_of_rain = sparkSession.sql("SELECT county, dFrom, dTo FROM RAIN, tb_metadata WHERE SID = stid ORDER BY (county, dFrom)")

    df_location_of_rain = Utility.coalesceWithPartition(sparkSession, df_location_of_rain.repartition(df_location_of_rain.col("county")).sortWithinPartitions("state", "dFrom"), "county", "dFrom", "dto")

    df_location_of_rain.createOrReplaceTempView("LOCATIONOFRAIN")

    scala.util.Try(sparkSession.sqlContext.dropTempTable("tb_metadata"))

    scala.util.Try(sparkSession.sqlContext.dropTempTable("RAIN"))

    //****************** SHOWERY COUNTY *******************//

    var df_showery_county = sparkSession.sql("SELECT LOCATIONOFRAIN.county AS county, \n" +
      "CASE \n" +
      "WHEN LOCATIONOFRAIN.dFrom > LOCATIONOFRAD.dFrom AND LOCATIONOFRAD.dTo > LOCATIONOFRAIN.dFrom THEN LOCATIONOFRAIN.dFrom \n" +
      "WHEN LOCATIONOFRAD.dFrom > LOCATIONOFRAIN.dFrom AND LOCATIONOFRAIN.dTo > LOCATIONOFRAD.dFrom THEN LOCATIONOFRAD.dFrom \n" +
      "WHEN LOCATIONOFRAIN.dFrom = LOCATIONOFRAD.dFrom THEN LOCATIONOFRAIN.dFrom \n" +
      "END AS dFrom, \n" +
      "CASE \n" +
      "WHEN LOCATIONOFRAIN.dTo < LOCATIONOFRAD.dTo AND LOCATIONOFRAIN.dTo > LOCATIONOFRAD.dFrom THEN LOCATIONOFRAIN.dTo \n" +
      "WHEN LOCATIONOFRAD.dTo < LOCATIONOFRAIN.dTo AND LOCATIONOFRAD.dTo > LOCATIONOFRAIN.dFrom THEN LOCATIONOFRAD.dTo \n" +
      "WHEN LOCATIONOFRAIN.dTo = LOCATIONOFRAD.dTo THEN LOCATIONOFRAIN.dTo \n" +
      "END AS dTo \n" +
      "FROM LOCATIONOFRAIN, LOCATIONOFRAD \n" +
      "WHERE LOCATIONOFRAIN.county = LOCATIONOFRAD.county AND \n" +
      "((LOCATIONOFRAIN.dFrom > LOCATIONOFRAD.dFrom AND LOCATIONOFRAD.dTo > LOCATIONOFRAIN.dFrom) OR (LOCATIONOFRAD.dFrom > LOCATIONOFRAIN.dFrom AND LOCATIONOFRAIN.dTo > LOCATIONOFRAD.dFrom) OR (LOCATIONOFRAIN.dFrom = LOCATIONOFRAD.dFrom)) AND \n" +
      "((LOCATIONOFRAIN.dTo < LOCATIONOFRAD.dTo AND LOCATIONOFRAIN.dTo > LOCATIONOFRAD.dFrom) OR (LOCATIONOFRAD.dTo < LOCATIONOFRAIN.dTo AND LOCATIONOFRAD.dTo > LOCATIONOFRAIN.dFrom) OR (LOCATIONOFRAIN.dTo = LOCATIONOFRAD.dTo))")

    df_showery_county.show(100000)

//    endRun = java.lang.System.currentTimeMillis()
//
//    val fw = new FileWriter(args(4), true)
//    fw.write(args(3) +"; "+(endLoad - startLoad)+"; "+(endRun - startRun)+"; "+TimeUnit.MILLISECONDS.toSeconds(endLoad - startLoad)+"; "+TimeUnit.MILLISECONDS.toSeconds(endRun - startRun))
//    fw.close()

    sparkSession.close()
  }

}

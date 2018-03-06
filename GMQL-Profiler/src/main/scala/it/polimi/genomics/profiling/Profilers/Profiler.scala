package it.polimi.genomics.profiling.Profilers

import it.polimi.genomics.core.DataTypes._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import it.polimi.genomics.profiling.Profiles.{GMQLDatasetProfile, GMQLSampleStats}
import org.apache.commons.math.stat.descriptive.summary.SumOfSquares
import org.slf4j.LoggerFactory

import scala.collection.Map
import scala.collection.mutable.ListBuffer
import scala.xml.Elem

/**
  * Created by andreagulino on 10/04/17.*
  */

object Profiler extends java.io.Serializable {

  final val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Get an XML representation of the profile for the web interface (partial features)
    *
    * @param profile
    * @return
    */
  def profileToWebXML(profile: GMQLDatasetProfile): Elem = {

    <dataset>
      <feature name="Number of samples">{profile.get(Feature.NUM_SAMP)}</feature>
      <feature name="Number of regions">{profile.get(Feature.NUM_REG)}</feature>
      <feature name="Average region length">{profile.get(Feature.AVG_REG_LEN)}</feature>
      <samples>
        { profile.samples.sortBy(x => x.name).map(y =>
        <sample name={y.name}>
          <feature name="Number of regions">{y.get(Feature.NUM_REG)}</feature>
          <feature name="Average region length">{y.get(Feature.AVG_REG_LEN)}</feature>
        </sample>
      )
        }
      </samples>
    </dataset>

  }

  /**
    * Get an XML representation of the profile for optimization (full features)
    *
    * @param profile
    * @return
    */
  def profileToOptXML(profile: GMQLDatasetProfile): Elem = {

    <dataset>
      {profile.stats.map(x => <feature name={x._1}>{x._2}</feature>)}
      <samples>
        { profile.samples.sortBy(x => x.name).map(y =>
        <sample id={y.ID} name={y.name}>
          {y.stats.map(z => <feature name={z._1}>{z._2}</feature>)}
        </sample>)
        }
      </samples>
    </dataset>

  }

  case class ProfilerValue(leftMost: Long, rightMost: Long, minLength: Long, maxLength: Long, sumLength: Long, sumLengthOfSquares: Long, count: Long)

  case class ProfilerResult(leftMost: Long, rightMost: Long, minLength: Long, maxLength: Long, count: Long, avgLength: Double, varianceLength: Double)


  val reduceFunc: ((ProfilerValue, ProfilerValue) => ProfilerValue) = {
    case (l: ProfilerValue, r: ProfilerValue) =>
      ProfilerValue(
        math.min(l.leftMost, r.leftMost),
        math.max(l.rightMost, r.rightMost),
        math.min(l.minLength, r.minLength),
        math.max(l.maxLength, r.maxLength),
        l.sumLength + r.sumLength,
        l.sumLengthOfSquares + r.sumLengthOfSquares,
        l.count + r.count
      )
  }

  val calculateResult: (ProfilerValue => ProfilerResult) = { profile =>
    val mean = profile.sumLength.toDouble / profile.count
    val variance = profile.sumLengthOfSquares.toDouble / profile.count - mean * mean
    ProfilerResult(profile.leftMost, profile.rightMost, profile.minLength, profile.maxLength, profile.count, mean, variance)
  }

  /**
    * Profile a dataset providing the RDD representation of
    *
    * @param regions
    * @param meta
    * @param sc Spark Contxt
    * @return the profile object
    */
  def profile(regions: RDD[GRECORD], meta: RDD[(Long, (String, String))], sc: SparkContext, namesOpt: Option[Map[Long, String]] = None): GMQLDatasetProfile = {

    // GRECORD    =  (GRecordKey,Array[GValue])
    // GRecordKey =  (id, chrom, start, stop, strand)
    // data       =  (id , (chr, width, start, stop, strand) )


    //    val outSample = s"S_%05d.gdm"
    //
    //    val Ids = meta.keys.distinct()
    //    val newIDS: Map[Long, String] = Ids.zipWithIndex().map(s => (s._1, outSample.format(s._2))).collectAsMap()
    //    val newIDSbroad = sc.broadcast(newIDS)

    val names = {
      namesOpt.getOrElse {
        val outSample = s"S_%05d.gdm"
        val Ids = meta.keys.distinct()
        val newIDS: Map[Long, String] = Ids.zipWithIndex().map(s => (s._1, outSample.format(s._2))).collectAsMap()
        val newIDSbroad = {
          val bc = sc.broadcast(newIDS)
          val res = bc.value
          bc.unpersist()
          res
        }
        newIDSbroad
      }
    }

    if (names.isEmpty) {
      logger.warn("Samples set is empty, returning.")
      GMQLDatasetProfile(List())
    } else {


      //remove the one that doesn't have corresponding meta
      val filtered = regions.filter(x => names.contains(x._1.id))


      //if we need chromosome
      val mappedSampleChrom = filtered
        .map { x =>
          val gRecordKey = x._1
          val distance = gRecordKey.stop - gRecordKey.start
          val profiler = ProfilerValue(gRecordKey.start, gRecordKey.stop, distance, distance, distance, distance * distance, 1)
          ((gRecordKey.id, gRecordKey.chrom), profiler)
        }


      val reducedSampleChrom = mappedSampleChrom.reduceByKey(reduceFunc)

      val mappedSample = reducedSampleChrom
        .map { x: ((Long, String), ProfilerValue) =>
          (x._1._1, x._2)
        }

      val reducedSample = mappedSample.reduceByKey(reduceFunc)


      val resultSamples: Map[Long, ProfilerValue] = reducedSample.collectAsMap()

      val resultSamplesToSave = resultSamples.map { inp => (inp._1, calculateResult(inp._2)) }


      val resultDsToSave = calculateResult(resultSamples.values.reduce(reduceFunc))


      def numToString(x: Double): String = {
        if (x % 1 == 0) {
          "%.0f".format(x)
        } else {
          "%.2f".format(x)
        }
      }

      logger.info("Profiling " + names.size + " samples.")

      val sampleProfiles = resultSamplesToSave.map { case (sampleId: Long, profile: ProfilerResult) =>

        val sample = GMQLSampleStats(ID = sampleId.toString)
        sample.name = names(sampleId)

        sample.stats_num += Feature.NUM_REG.toString -> profile.count
        sample.stats_num += Feature.AVG_REG_LEN.toString -> profile.avgLength
        sample.stats_num += Feature.MIN_COORD.toString -> profile.leftMost
        sample.stats_num += Feature.MAX_COORD.toString -> profile.rightMost

        sample.stats_num += Feature.MIN_LENGTH.toString -> profile.minLength
        sample.stats_num += Feature.MAX_LENGTH.toString -> profile.maxLength
        sample.stats_num += Feature.VARIANCE_LENGTH.toString -> profile.varianceLength

        sample.stats = sample.stats_num.map(x => (x._1, numToString(x._2)))

        sample
      }

      val dsprofile = GMQLDatasetProfile(samples = sampleProfiles.toList)
//
//
//      val totReg = resultDsToSave.count
////        sampleProfiles.map(x => x.stats_num.get(Feature.NUM_REG.toString).get).reduce((x, y) => x + y)
//      val sumAvg = sampleProfiles.map(x => x.stats_num.get(Feature.AVG_REG_LEN.toString).get).reduce((x, y) => x + y)
//
//
//      val totAvg = sumAvg / samples.size


      dsprofile.stats += Feature.NUM_REG.toString -> numToString(resultDsToSave.count)
      dsprofile.stats += Feature.AVG_REG_LEN.toString -> numToString(resultDsToSave.avgLength)
      dsprofile.stats += Feature.MIN_COORD.toString -> numToString(resultDsToSave.leftMost)
      dsprofile.stats += Feature.MAX_COORD.toString -> numToString(resultDsToSave.rightMost)

      dsprofile.stats += Feature.MIN_LENGTH.toString -> numToString(resultDsToSave.minLength)
      dsprofile.stats += Feature.MAX_LENGTH.toString -> numToString(resultDsToSave.maxLength)
      dsprofile.stats += Feature.VARIANCE_LENGTH.toString -> numToString(resultDsToSave.varianceLength)

      dsprofile

    }

  }

}


object Feature extends Enumeration {
  type Feature = Value
  val NUM_SAMP: Feature.Value = Value("num_samp")
  val NUM_REG: Feature.Value = Value("num_reg")
  val AVG_REG_LEN: Feature.Value = Value("avg_reg_length")
  val MIN_COORD: Feature.Value = Value("min")
  val MAX_COORD: Feature.Value = Value("max")

  val MIN_LENGTH: Feature.Value = Value("min_length")
  val MAX_LENGTH: Feature.Value = Value("max_length")
  val VARIANCE_LENGTH: Feature.Value = Value("variance_length")

}
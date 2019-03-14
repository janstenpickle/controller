package io.janstenpickle.controller.cache.monitoring

import java.lang.management.ManagementFactory
import java.util

import io.prometheus.client.{Collector, CounterMetricFamily, GaugeMetricFamily}
import javax.management._
import org.cache2k.jmx.CacheMXBean

import scala.collection.JavaConverters.{asScalaSetConverter, mapAsScalaMapConverter, seqAsJavaListConverter}

class CacheCollector extends Collector {
  private lazy val mBeanServer = ManagementFactory.getPlatformMBeanServer

  private def cacheMBeans: List[(String, CacheMXBean)] =
    mBeanServer
      .queryNames(null, null)
      .asScala
      .toList
      .collect {
        case obj
            if obj.getDomain == "org.cache2k" && obj.getKeyPropertyList.asScala.get("type").fold(false)(_ == "Cache") =>
          obj.getKeyProperty("name") -> JMX.newMBeanProxy(mBeanServer, obj, classOf[CacheMXBean], true)
      }

  def collectSamples: List[Collector.MetricFamilySamples] = cacheMBeans.flatMap {
    case (cacheName, bean) =>
      val value = bean.getValueType

      def newGauge(name: String, help: String, v: Double) =
        new GaugeMetricFamily(s"cache2k_$name", help, List("cache_name", "cache_value_type").asJava)
          .addMetric(List(cacheName, value).asJava, v)

      def newCounter(name: String, help: String, v: Double) =
        new CounterMetricFamily(s"cache2k_$name", help, List("cache_name", "cache_value_type").asJava)
          .addMetric(List(cacheName, value).asJava, v)

      List(
        newGauge("current_objects", "Current number of objects in cache", bean.getSize.toDouble),
        newGauge("hit_rate", "Cache hit rate", bean.getHitRate),
        newGauge("hash_quality", "Cache hash quality", bean.getHashQuality.toDouble),
        newCounter("get_count", "Number of gets on cache", bean.getGetCount.toDouble),
        newCounter("put_count", "Number of puts on cache", bean.getPutCount.toDouble),
        newCounter("miss_count", "Number of misses on cache", bean.getMissCount.toDouble),
        newCounter("expired_count", "Number of expires on cache", bean.getExpiredCount.toDouble),
      )
  }

  override def collect(): util.List[Collector.MetricFamilySamples] = collectSamples.asJava
}

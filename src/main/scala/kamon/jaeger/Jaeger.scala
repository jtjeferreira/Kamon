/*
 * =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.jaeger

import java.util

import com.typesafe.config.Config
import com.uber.jaeger.agent.thrift.Agent
import com.uber.jaeger.thriftjava.{Batch, Log, Process, Tag, TagType, Span => JaegerSpan}
import kamon.trace.Span
import kamon.{Kamon, SpanReporter}
import org.apache.thrift.protocol.TCompactProtocol

class Jaeger() extends SpanReporter {
  override def reconfigure(config: Config): Unit = {}
  override def start(): Unit = {}
  override def stop(): Unit = {}

  val jaegerClient = new JaegerClient()


  override def reportSpans(spans: Seq[Span.CompletedSpan]): Unit = {
    spans.foreach(s => jaegerClient.sendSpan(s))
  }
}


class JaegerClient() {
  import scala.collection.JavaConverters._

  val transport = TUDPTransport.NewTUDPClient("jaeger", 5775)
  val client = new Agent.Client(new TCompactProtocol(transport))
  val process = new Process(Kamon.environment.service)


  def sendSpan(span: Span.CompletedSpan): Unit = {
    client.emitBatch(new Batch(process, Seq(convertSpan(span)).asJava))
  }

  private def convertSpan(span: Span.CompletedSpan): JaegerSpan = {
    val convertedSpan = new JaegerSpan(
      span.context.traceID,
      0L,
      span.context.spanID,
      span.context.parentID,
      span.operationName,
      0,
      span.startTimestampMicros,
      span.endTimestampMicros - span.startTimestampMicros
    )

    val convertedLogs = span.logs.map { log =>
      val convertedFields = log.fields.map(convertField)
      new Log(log.timestamp, convertedFields.toList.asJava)
    }
    convertedSpan.setLogs(convertedLogs.asJava)

    convertedSpan.setTags(new util.ArrayList[Tag](span.tags.size))
    span.tags.foreach {
      case (k, v) =>
        val tag = new Tag(k, TagType.STRING)
        tag.setVStr(v)
        convertedSpan.tags.add(tag)
    }

    convertedSpan
  }

  private def convertField(field: (String, _)): Tag = {
    val (tagType, setFun) = field._2 match {
      case v: String =>      (TagType.STRING, (tag: Tag) => tag.setVStr(v))
      case v: Double =>      (TagType.DOUBLE, (tag: Tag) => tag.setVDouble(v))
      case v: Boolean =>     (TagType.BOOL,   (tag: Tag) => tag.setVBool(v))
      case v: Long =>        (TagType.LONG,   (tag: Tag) => tag.setVLong(v))
      case v: Array[Byte] => (TagType.BINARY, (tag: Tag) => tag.setVBinary(v))
      case v => throw new RuntimeException(s"Tag type ${v.getClass.getName} not supported")
    }
    val convertedTag = new Tag(field._1, tagType)
    setFun(convertedTag)
    convertedTag
  }
}

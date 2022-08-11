package ai.privado.exporter

import ai.privado.model.{CatLevelOne, Constants, InternalTag}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{CfgNode, StoredNode, Tag}
import io.shiftleft.semanticcpg.language._
import io.circe._
import io.circe.syntax._

import scala.collection.mutable.{HashMap, LinkedHashMap, Set}
import ai.privado.cache.RuleCache
import ai.privado.entrypoint.ScanProcessor
import ai.privado.semantic.Language.finder
import overflowdb.traversal.Traversal

class SourceExporter(cpg: Cpg) {

  lazy val sourcesTagList = getSourcesTagList
  lazy val sourcesList    = getSourcesList

  /** Fetch and Convert sources to desired output
    */
  def getSources = {
    convertSourcesList(sourcesTagList)
  }

  def getProcessing = {

    val processingMap = HashMap[String, Set[CfgNode]]()
    sourcesList.foreach(source => {
      def addToMap(sourceId: String): Unit = {
        if (processingMap.contains(sourceId)) {
          processingMap(sourceId) = processingMap(sourceId).addOne(source)
        } else {
          processingMap.addOne(sourceId -> Set(source))
        }
      }
      if (source.tag.nameExact(Constants.catLevelOne).value.head.equals(CatLevelOne.SOURCES.name)) {
        source.tag.nameExact(Constants.id).value.foreach(addToMap)
      } else {
        source.tag.name(Constants.privadoDerived + ".*").value.foreach(addToMap)
      }
    })
    processingMap.map(entrySet =>
      LinkedHashMap[String, Json](
        Constants.sourceId -> entrySet._1.asJson,
        Constants.occurrences -> ExporterUtility
          .convertPathElements({
            if (ScanProcessor.config.disableDeDuplication)
              entrySet._2.toList
            else
              entrySet._2.toList.distinctBy(_.code).distinctBy(_.lineNumber).distinctBy(_.location.filename)
          })
          .asJson
      )
    )
  }

  /** Fetch all the sources tag
    */
  private def getSourcesTagList = {
    def filterSource(traversal: Traversal[StoredNode]) = {
      traversal.tag
        .nameExact(Constants.catLevelOne)
        .or(_.valueExact(CatLevelOne.SOURCES.name), _.valueExact(CatLevelOne.DERIVED_SOURCES.name))
    }
    val sources =
      cpg.identifier
        .where(filterSource)
        .map(item => item.tag.l)
        .l ++
        cpg.literal
          .where(filterSource)
          .map(item => item.tag.l)
          .l ++
        cpg.call
          .where(filterSource)
          .map(item => item.tag.l)
          .l
    sources
  }

  /** Fetch all the sources node
    */
  private def getSourcesList: List[CfgNode] = {
    def filterSource(traversal: Traversal[StoredNode]) = {
      traversal.tag
        .nameExact(Constants.catLevelOne)
        .or(_.valueExact(CatLevelOne.SOURCES.name), _.valueExact(CatLevelOne.DERIVED_SOURCES.name))
    }
    val sources =
      cpg.identifier
        .where(filterSource)
        .l ++
        cpg.literal
          .where(filterSource)
          .l ++
        cpg.call
          .where(filterSource)
          .l
    sources
  }

  private def convertSourcesList(sources: List[List[Tag]]) = {
    def convertSource(sourceId: String) = {
      val orderedSourceMap = new LinkedHashMap[String, Json]()
      RuleCache.getRuleInfo(sourceId) match {
        case Some(rule) =>
          orderedSourceMap.addOne(Constants.sourceType -> rule.catLevelOne.label.asJson)
          Some(orderedSourceMap ++ ExporterUtility.getRuleInfoForExporting(sourceId))
        case None => // not found anything, probably derived source
          None
      }
    }

    def getSources(nodeList: List[Tag]) = {
      val node = nodeList
        .filterNot(node => InternalTag.valuesAsString.contains(node.name))
        .filter(node => node.name.equals(Constants.id) || node.name.startsWith(Constants.privadoDerived))
      if (node.nonEmpty) {
        Some(node.value.toSet)
      } else
        None
    }
    sources
      .flatMap(source => getSources(source))
      .flatten
      .filter(_.nonEmpty)
      .toSet
      .flatMap(source => convertSource(source))
  }

}

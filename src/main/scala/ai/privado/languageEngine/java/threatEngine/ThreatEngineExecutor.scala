/*
 * This file is part of Privado OSS.
 *
 * Privado is an open source static code analysis tool to discover data flows in the code.
 * Copyright (C) 2022 Privado, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, contact support@privado.ai
 *
 */

package ai.privado.languageEngine.java.threatEngine

import ai.privado.exporter.ExporterUtility
import ai.privado.model.exporter.ViolationModel
import ai.privado.model.PolicyOrThreat
import ai.privado.utility.Utilities._
import io.joern.dataflowengineoss.language.Path
import io.shiftleft.codepropertygraph.generated.Cpg
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

class ThreatEngineExecutor(cpg: Cpg, dataflows: Map[String, Path], repoPath: String) {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Process and return the violating threats of processing type
    * @param threats
    *   threats
    * @return
    */
  def getProcessingViolations(threats: List[PolicyOrThreat]): List[ViolationModel] = {
    threats.flatMap(threat => processProcessingViolations(threat))
  }

  /** Process and return the violating threats of dataflow type
    * @param threats
    *   threats
    * @return
    */
  def getDataflowViolations(threats: List[PolicyOrThreat]): List[ViolationModel] = {
    threats.flatMap(threat => processDataflowViolations(threat))
  }

  private def processProcessingViolations(threat: PolicyOrThreat) = {
    val threatId = threat.id
    logger.info(s"Processing 'processing' threat: ${threatId}")
    // process android threats

    // if we get a manifest file, consider android app
    // and process android-specific threats
    val (isAndroidRepo, manifestFile) = getAndroidManifestFile(repoPath)

    val violationResponse = threatId match {
      case "Threats.Collection.isKeyboardCacheUsed" if isAndroidRepo =>
        KeyboardCache.getViolations(repoPath) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }

      case "Threats.Storage.isAppDataBackupAllowed" if isAndroidRepo =>
        SensitiveDataBackup.getViolations(cpg, manifestFile) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }

      case "Threats.Configuration.Mobile.isBackgroundScreenshotEnabled" if isAndroidRepo =>
        BackgroundScreenshot.getViolations(cpg) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }

      case "Threats.Sharing.isIpcDataSharingAllowed" if isAndroidRepo =>
        DataSharingIPC.getViolations(cpg, manifestFile) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }

      case "Threats.Collection.isInputMasked" if isAndroidRepo =>
        SensitiveInputMask.getViolations(cpg, repoPath) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }

      case "Threats.Storage.isDataStoredOnExternalStorage" if isAndroidRepo =>
        DataOnExternalStorage.getViolations(cpg, manifestFile) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }

      case _ =>
        logger.debug(s"No implementation detected for threat: ${threatId} (isAndroidRepo: ${isAndroidRepo})")
        None
    }

    violationResponse match {
      case Some((isThreat, occurrences)) if isThreat =>
        Some(
          ViolationModel(
            threatId,
            ExporterUtility.getPolicyInfoForExporting(threatId),
            None, {
              if (occurrences.nonEmpty) Some(occurrences) else None
            }
          )
        )
      case _ => None
    }

  }

  private def processDataflowViolations(threat: PolicyOrThreat) = {
    val threatId = threat.id
    logger.info(s"Processing 'dataflow' threat: ${threatId}")

    // if we get a manifest file, consider android app
    // and process android-specific threats
    val (isAndroidRepo, _) = getAndroidManifestFile(repoPath)

    val violationResponse = threatId match {
      case "Threats.Sharing.isDataExposedToThirdPartiesViaNotification" if isAndroidRepo =>
        DataLeakageToNotifications.getViolations(threat, cpg, dataflows) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }
      case "Threats.Leakage.isDataLeakingToLog" =>
        DataLeakageToLogs.getViolations(threat, cpg, dataflows) match {
          case Success(res) => Some(res)
          case Failure(_)   => None
        }
      case _ =>
        logger.debug(s"No implementation detected for threat: ${threatId} (isAndroidRepo: ${isAndroidRepo})")
        None
    }

    violationResponse match {
      case Some((isThreat, dataflows)) if isThreat =>
        Some(ViolationModel(threatId, ExporterUtility.getPolicyInfoForExporting(threatId), Some(dataflows), None))
      case _ => None
    }
  }

  private def getAndroidManifestFile(repoPath: String): (Boolean, String) = {
    val manifestFile = getAllFilesRecursively(repoPath, Set(".xml")) match {
      case Some(sourceFileNames) => {
        sourceFileNames.find(_.endsWith("AndroidManifest.xml"))
      }
      case None => None
    }

    manifestFile match {
      case Some(manifestFile) =>
        logger.debug(s"Found AndroidManifest.xml: ${manifestFile}")
        (true, manifestFile)
      case _ =>
        logger.debug("Did not find AndroidManifest.xml")
        (false, "")
    }
  }
}

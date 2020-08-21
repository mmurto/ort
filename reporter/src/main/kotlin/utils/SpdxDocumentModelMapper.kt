/*
 * Copyright (C) 2020 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.reporter.utils

import java.time.Instant
import java.util.UUID

import org.ossreviewtoolkit.model.Environment
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.utils.PackageConfigurationProvider
import org.ossreviewtoolkit.model.utils.getDetectedLicensesWithCopyrights
import org.ossreviewtoolkit.reporter.LicenseTextProvider
import org.ossreviewtoolkit.spdx.SpdxConstants
import org.ossreviewtoolkit.spdx.SpdxExpression
import org.ossreviewtoolkit.spdx.SpdxLicense
import org.ossreviewtoolkit.spdx.SpdxLicenseException
import org.ossreviewtoolkit.spdx.model.SpdxCreationInfo
import org.ossreviewtoolkit.spdx.model.SpdxDocument
import org.ossreviewtoolkit.spdx.model.SpdxExtractedLicenseInfo
import org.ossreviewtoolkit.spdx.model.SpdxPackage
import org.ossreviewtoolkit.spdx.model.SpdxPackageVerificationCode
import org.ossreviewtoolkit.spdx.model.SpdxRelationship
import org.ossreviewtoolkit.utils.ORT_FULL_NAME
import org.ossreviewtoolkit.utils.ProcessedDeclaredLicense
import org.ossreviewtoolkit.utils.stripCredentialsFromUrl

/**
 * A class for mapping [OrtResult]s to [SpdxDocument]s.
 */
object SpdxDocumentModelMapper {
    data class SpdxDocumentParams(
        val documentName: String,
        val documentComment: String,
        val creationInfoComment: String
    )

    fun map(
        ortResult: OrtResult,
        packageConfigurationProvider: PackageConfigurationProvider,
        licenseTextProvider: LicenseTextProvider,
        params: SpdxDocumentParams
    ): SpdxDocument {
        val spdxPackageIdGenerator = SpdxPackageIdGenerator()
        val packages = mutableListOf<SpdxPackage>()
        val relationships = mutableListOf<SpdxRelationship>()

        val rootPackage = SpdxPackage(
            spdxId = spdxPackageIdGenerator.nextId("root-package"),
            copyrightText = SpdxConstants.NOASSERTION,
            downloadLocation = SpdxConstants.NOASSERTION,
            filesAnalyzed = false,
            homepage = SpdxConstants.NOASSERTION,
            licenseConcluded = SpdxConstants.NOASSERTION,
            licenseDeclared = SpdxConstants.NOASSERTION,
            name = "Root package"
        )
        packages.add(rootPackage)

        ortResult.getPackages(omitExcluded = true).forEach { curatedPackage ->
            val pkg = curatedPackage.pkg

            val binaryPackage = SpdxPackage(
                spdxId = spdxPackageIdGenerator.nextId(pkg.id.name),
                copyrightText = getSpdxCopyrightText(ortResult, packageConfigurationProvider, pkg.id),
                downloadLocation = pkg.binaryArtifact.url.nullOrBlankToSpdxNone(),
                filesAnalyzed = false,
                homepage = pkg.homepageUrl.nullOrBlankToSpdxNone(),
                licenseConcluded = pkg.concludedLicense.nullOrBlankToSpdxNoassertionOrNone(),
                licenseDeclared = pkg.declaredLicensesProcessed.toSpdxDeclaredLicense(),
                name = pkg.id.toSpdxPackageName(),
                summary = pkg.description.nullOrBlankToSpdxNone(),
                versionInfo = pkg.id.version
            )

            val binaryPackageRelationship = SpdxRelationship(
                spdxElementId = binaryPackage.spdxId,
                relatedSpdxElement = rootPackage.spdxId,
                relationshipType = SpdxRelationship.Type.DEPENDENCY_OF
            )

            packages.add(binaryPackage)
            relationships.add(binaryPackageRelationship)

            if (pkg.vcsProcessed.url.isNotBlank()) {
                val vcsScanResult =
                    ortResult.getScanResultsForId(curatedPackage.pkg.id).find { it.provenance.vcsInfo != null }

                // TODO: The copyright text contains copyrights from all scan results.
                val vcsPackage = binaryPackage.copy(
                    spdxId = spdxPackageIdGenerator.nextId("${pkg.id.name}-vcs"),
                    filesAnalyzed = vcsScanResult != null,
                    downloadLocation = pkg.vcsProcessed.toSpdxDownloadLocation(),
                    licenseConcluded = SpdxConstants.NOASSERTION,
                    licenseDeclared = SpdxConstants.NOASSERTION,
                    packageVerificationCode = vcsScanResult?.toSpdxPackageVerificationCode()
                )

                val vcsPackageRelationShip = SpdxRelationship(
                    spdxElementId = binaryPackage.spdxId,
                    relatedSpdxElement = vcsPackage.spdxId,
                    relationshipType = SpdxRelationship.Type.GENERATED_FROM
                )

                packages.add(vcsPackage)
                relationships.add(vcsPackageRelationShip)
            }

            if (pkg.sourceArtifact.url.isNotBlank()) {
                val sourceArtifactScanResult =
                    ortResult.getScanResultsForId(curatedPackage.pkg.id).find { it.provenance.sourceArtifact != null }

                // TODO: The copyright text contains copyrights from all scan results.
                val sourceArtifactPackage = binaryPackage.copy(
                    spdxId = spdxPackageIdGenerator.nextId("${curatedPackage.pkg.id.name}-source-artifact"),
                    filesAnalyzed = sourceArtifactScanResult != null,
                    downloadLocation = curatedPackage.pkg.sourceArtifact.url.nullOrBlankToSpdxNone(),
                    licenseConcluded = SpdxConstants.NOASSERTION,
                    licenseDeclared = SpdxConstants.NOASSERTION,
                    packageVerificationCode = sourceArtifactScanResult?.toSpdxPackageVerificationCode()
                )

                val sourceArtifactPackageRelationship = SpdxRelationship(
                    spdxElementId = binaryPackage.spdxId,
                    relatedSpdxElement = sourceArtifactPackage.spdxId,
                    relationshipType = SpdxRelationship.Type.GENERATED_FROM
                )

                packages.add(sourceArtifactPackage)
                relationships.add(sourceArtifactPackageRelationship)
            }
        }

        return SpdxDocument(
            comment = params.documentComment,
            creationInfo = SpdxCreationInfo(
                comment = params.creationInfoComment,
                created = Instant.now(),
                creators = listOf("Tool: $ORT_FULL_NAME - ${Environment().ortVersion}"),
                licenseListVersion = SpdxLicense.LICENSE_LIST_VERSION.substringBefore("-")
            ),
            documentNamespace = "spdx://${UUID.randomUUID()}",
            documentDescribes = listOf(rootPackage.spdxId),
            name = params.documentName,
            packages = packages,
            relationships = relationships.sortedBy { it.spdxElementId }
        ).addExtractedLicenseInfo(licenseTextProvider)
    }
}

private class SpdxPackageIdGenerator {
    var nextPackageIndex = 0

    fun nextId(name: String): String =
        buildString {
            append("SPDXRef-Package-${nextPackageIndex++}")
            if (name.isNotBlank()) {
                append("-$name")
            }
        }
}

private fun getSpdxCopyrightText(
    ortResult: OrtResult,
    packageConfigurationProvider: PackageConfigurationProvider,
    id: Identifier
): String {
    val copyrightStatements = ortResult.getDetectedLicensesWithCopyrights(id, packageConfigurationProvider)
        .flatMap { it.value }
        .distinct()
        .sorted()

    return if (copyrightStatements.isNotEmpty()) {
        copyrightStatements.joinToString("\n")
    } else {
        SpdxConstants.NONE
    }
}

private fun Identifier.toSpdxPackageName(): String = "$type:$namespace:$name"

private fun ProcessedDeclaredLicense.toSpdxDeclaredLicense(): String =
    when {
        unmapped.isEmpty() -> spdxExpression.nullOrBlankToSpdxNoassertionOrNone()
        spdxExpression == null -> SpdxConstants.NOASSERTION
        spdxExpression.toString().isBlank() -> SpdxConstants.NOASSERTION
        spdxExpression.toString() == SpdxConstants.NONE -> SpdxConstants.NOASSERTION
        else -> spdxExpression.toString()
    }

private fun String?.nullOrBlankToSpdxNone(): String = if (isNullOrBlank()) SpdxConstants.NONE else this

private fun ScanResult.toSpdxPackageVerificationCode(): SpdxPackageVerificationCode =
    SpdxPackageVerificationCode(
        packageVerificationCodeExcludedFiles = emptyList(),
        packageVerificationCodeValue = summary.packageVerificationCode.toLowerCase()
    )

private fun SpdxDocument.addExtractedLicenseInfo(licenseTextProvider: LicenseTextProvider): SpdxDocument {

    val nonSpdxLicenses = packages.flatMapTo(mutableSetOf()) {
        // TODO: Also add detected non-SPDX licenses here.
        SpdxExpression.parse(it.licenseConcluded).licenses() + SpdxExpression.parse(it.licenseDeclared).licenses()
    }.filter {
        SpdxConstants.isPresent(it) && SpdxLicense.forId(it) == null && SpdxLicenseException.forId(it) == null
    }

    val extractedLicenseInfo = nonSpdxLicenses.sorted().mapNotNull { license ->
        licenseTextProvider.getLicenseText(license)?.let { text ->
            SpdxExtractedLicenseInfo(
                licenseId = license,
                extractedText = text
            )
        }
    }

    return copy(hasExtractedLicensingInfos = extractedLicenseInfo)
}

private fun SpdxExpression?.nullOrBlankToSpdxNoassertionOrNone(): String =
    when {
        this == null -> SpdxConstants.NOASSERTION
        toString().isBlank() -> SpdxConstants.NONE
        else -> toString()
    }

private fun VcsInfo.toSpdxDownloadLocation(): String {
    val vcsTool = when (type) {
        VcsType.CVS -> "cvs"
        VcsType.GIT -> "git"
        VcsType.GIT_REPO -> "repo"
        VcsType.MERCURIAL -> "hg"
        VcsType.SUBVERSION -> "svn"
        else -> type.aliases.first().toLowerCase()
    }

    return buildString {
        append(vcsTool)
        if (vcsTool.isNotEmpty()) append("+")
        append(url.stripCredentialsFromUrl())
        if (!resolvedRevision.isNullOrBlank()) append("@$resolvedRevision")
        if (path.isNotBlank()) append("#$path")
    }
}

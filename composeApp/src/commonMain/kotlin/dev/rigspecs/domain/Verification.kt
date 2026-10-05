package dev.rigspecs.domain

import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity

/**
 * The status a spec gets when an editor saves the form.
 *
 * - A value an editor types or changes is theirs: VERIFIED, stamped with their name.
 * - An UNVERIFIED row stays unverified unless they chose Confirm.
 * - Changing an API-sourced value makes it a manual entry: the API no longer backs it.
 * - Saving notes or the category alone keeps the existing verification stamp.
 */
fun stampSpecSave(old: SpecItemEntity?, draft: SpecItemEntity, confirm: Boolean, userId: String, now: Long): SpecItemEntity {
    val valueChanged = old == null || old.value.trim() != draft.value.trim() || old.unit.trim() != draft.unit.trim()
    val wasUnverified = old?.status == VerificationStatus.UNVERIFIED
    val sourced = old?.sourceKind == SourceKind.OPENLABOR || old?.sourceKind == SourceKind.VPIC
    val status = if (wasUnverified && !confirm) VerificationStatus.UNVERIFIED else VerificationStatus.VERIFIED
    val stamp = status == VerificationStatus.VERIFIED && draft.value.isNotBlank() && (valueChanged || wasUnverified)
    val becameManual = valueChanged && sourced && old != null
    return draft.copy(
        status = status,
        sourceKind = if (becameManual) SourceKind.MANUAL else draft.sourceKind,
        sourceUrl = if (becameManual) "" else draft.sourceUrl,
        verifiedBy = when {
            stamp -> userId
            draft.value.isBlank() -> ""
            else -> old?.verifiedBy ?: draft.verifiedBy
        },
        verifiedAt = when {
            stamp -> now
            draft.value.isBlank() -> null
            else -> old?.verifiedAt ?: draft.verifiedAt
        },
    )
}

/** [stampSpecSave] for parts; the "value" is the set of part numbers. */
fun stampPartSave(old: PartEntity?, draft: PartEntity, confirm: Boolean, userId: String, now: Long): PartEntity {
    fun numbers(p: PartEntity) = (listOf(p.oemNumber) + p.aftermarketNumbers.lines()).map { it.trim() }.filter { it.isNotEmpty() }
    val hasNumbers = numbers(draft).isNotEmpty()
    val valueChanged = old == null || numbers(old) != numbers(draft)
    val wasUnverified = old?.status == VerificationStatus.UNVERIFIED
    val sourced = old?.sourceKind == SourceKind.OPENLABOR || old?.sourceKind == SourceKind.VPIC
    val status = if (wasUnverified && !confirm) VerificationStatus.UNVERIFIED else VerificationStatus.VERIFIED
    val stamp = status == VerificationStatus.VERIFIED && hasNumbers && (valueChanged || wasUnverified)
    val becameManual = valueChanged && sourced && old != null
    return draft.copy(
        status = status,
        sourceKind = if (becameManual) SourceKind.MANUAL else draft.sourceKind,
        sourceUrl = if (becameManual) "" else draft.sourceUrl,
        verifiedBy = when {
            stamp -> userId
            !hasNumbers -> ""
            else -> old?.verifiedBy ?: draft.verifiedBy
        },
        verifiedAt = when {
            stamp -> now
            !hasNumbers -> null
            else -> old?.verifiedAt ?: draft.verifiedAt
        },
    )
}

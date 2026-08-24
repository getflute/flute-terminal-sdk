package com.flute.terminal.sdk.model

import java.math.BigDecimal

/**
 * The complete money picture of a processed payment — what was actually charged and how it breaks
 * down. On a Dual-Pricing / surcharge / tip sale the total can differ from the requested base
 * amount, so an ISV must reconcile orders against [totalAmount], never the amount it sent.
 */
data class AmountBreakdown(
    /** Grand total actually processed on the tender. */
    val totalAmount: BigDecimal?,
    val baseAmount: BigDecimal?,
    val tipAmount: BigDecimal?,
    val surchargeAmount: BigDecimal?,
    val discountAmount: BigDecimal?,
    /** Rates as raw percentages (e.g. 3 = 3%). */
    val tipRate: BigDecimal?,
    val surchargeRate: BigDecimal?,
    val discountRate: BigDecimal?,
)

/**
 * PCI-safe card summary — exactly what a card-present receipt needs (masked PAN + brand are
 * required receipt fields). Never contains a full PAN or any sensitive authentication data.
 */
data class CardInfo(
    /** Masked PAN, e.g. "411111******1111". */
    val maskedPan: String?,
    /** Brand, e.g. "Visa", "MasterCard". */
    val brand: String?,
    /** The card's own type: "Credit" or "Debit". */
    val cardType: String?,
    /** How it was processed (a debit card may process as credit). */
    val processedAsType: String?,
    /** Entry mode, e.g. "EMV", "Contactless", "Swipe", "Manual". */
    val entryMode: String?,
    /** Cardholder verification, e.g. "OnlinePin", "ManualSignature". */
    val cardholderVerificationMethod: String?,
)

/**
 * Processor-side references. Auth code + RRN are card-present receipt requirements and the ids
 * support/disputes ask for — surface them so the ISV never has to call the API for them.
 */
data class ProcessorReferences(
    val authCode: String?,
    /** Retrieval reference number. */
    val rrn: String?,
    val mid: String?,
    val tid: String?,
)

/** Address-verification outcome for keyed-in payments. */
data class AvsResult(
    /** "Allow" or "Deny". */
    val action: String?,
    /** AVS response code, e.g. "N", "U", "Y". */
    val responseCode: String?,
    val description: String?,
)

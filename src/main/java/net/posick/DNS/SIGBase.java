// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package net.posick.DNS;

import java.io.IOException;
import java.time.Instant;

import net.posick.DNS.Compression;
import net.posick.DNS.DNSInput;
import net.posick.DNS.DNSOutput;
import net.posick.DNS.DNSSEC;
import net.posick.DNS.Name;
import net.posick.DNS.Options;
import net.posick.DNS.RRset;
import net.posick.DNS.Record;
import net.posick.DNS.TTL;
import net.posick.DNS.Tokenizer;
import net.posick.DNS.Type;
import net.posick.DNS.utils.base64;

/**
 * The base class for SIG/RRSIG records, which have identical formats
 *
 * @author Brian Wellington
 */
abstract class SIGBase extends net.posick.DNS.Record {
  protected int covered;
  protected int alg, labels;
  protected long origttl;
  protected Instant expire;
  protected Instant timeSigned;
  protected int footprint;
  protected net.posick.DNS.Name signer;
  protected byte[] signature;

  protected SIGBase() {}

  public SIGBase(
      net.posick.DNS.Name name,
      int type,
      int dclass,
      long ttl,
      int covered,
      int alg,
      long origttl,
      Instant expire,
      Instant timeSigned,
      int footprint,
      net.posick.DNS.Name signer,
      byte[] signature) {
    super(name, type, dclass, ttl);
    net.posick.DNS.Type.check(covered);
    TTL.check(origttl);
    this.covered = covered;
    this.alg = checkU8("alg", alg);
    this.labels = name.labels() - 1;
    if (name.isWild()) {
      this.labels--;
    }
    this.origttl = origttl;
    this.expire = expire;
    this.timeSigned = timeSigned;
    this.footprint = checkU16("footprint", footprint);
    this.signer = checkName("signer", signer);
    this.signature = signature;
  }

  @Override
  protected void rrFromWire(DNSInput in) throws IOException {
    covered = in.readU16();
    alg = in.readU8();
    labels = in.readU8();
    origttl = in.readU32();
    expire = Instant.ofEpochSecond(in.readU32());
    timeSigned = Instant.ofEpochSecond(in.readU32());
    footprint = in.readU16();
    signer = new net.posick.DNS.Name(in);
    signature = in.readByteArray();
  }

  @Override
  protected void rdataFromString(Tokenizer st, net.posick.DNS.Name origin) throws IOException {
    String typeString = st.getString();
    covered = net.posick.DNS.Type.value(typeString);
    if (covered < 0) {
      throw st.exception("Invalid type: " + typeString);
    }
    String algString = st.getString();
    alg = DNSSEC.Algorithm.value(algString);
    if (alg < 0) {
      throw st.exception("Invalid algorithm: " + algString);
    }
    labels = st.getUInt8();
    origttl = st.getTTL();
    expire = net.posick.DNS.FormattedTime.parse(st.getString());
    timeSigned = net.posick.DNS.FormattedTime.parse(st.getString());
    footprint = st.getUInt16();
    signer = st.getName(origin);
    signature = st.getBase64();
  }

  /** Converts the RRSIG/SIG Record to a String */
  @Override
  protected String rrToString() {
    StringBuilder sb = new StringBuilder();
    sb.append(net.posick.DNS.Type.string(covered));
    sb.append(" ");
    sb.append(alg);
    sb.append(" ");
    sb.append(labels);
    sb.append(" ");
    sb.append(origttl);
    sb.append(" ");
    if (net.posick.DNS.Options.check("multiline")) {
      sb.append("(\n\t");
    }
    sb.append(net.posick.DNS.FormattedTime.format(expire));
    sb.append(" ");
    sb.append(net.posick.DNS.FormattedTime.format(timeSigned));
    sb.append(" ");
    sb.append(footprint);
    sb.append(" ");
    sb.append(signer);
    if (Options.check("multiline")) {
      sb.append("\n");
      sb.append(base64.formatString(signature, 64, "\t", true));
    } else {
      sb.append(" ");
      sb.append(base64.toString(signature));
    }
    return sb.toString();
  }

  /** Returns the RRset type covered by this signature */
  public int getTypeCovered() {
    return covered;
  }

  /**
   * Returns the type of RRset that this record would belong to. For all types except SIG/RRSIG,
   * this is equivalent to getType().
   *
   * @return The type of record
   * @see Type
   * @see RRset
   * @see Record#getRRsetType()
   */
  @Override
  public int getRRsetType() {
    return covered;
  }

  /** Returns the cryptographic algorithm of the key that generated the signature */
  public int getAlgorithm() {
    return alg;
  }

  /**
   * Returns the number of labels in the signed domain name. This may be different than the record's
   * domain name if the record is a wildcard record.
   */
  public int getLabels() {
    return labels;
  }

  /** Returns the original TTL of the RRset */
  public long getOrigTTL() {
    return origttl;
  }

  /** Returns the time at which the signature expires */
  public Instant getExpire() {
    return expire;
  }

  /** Returns the time at which this signature was generated */
  public Instant getTimeSigned() {
    return timeSigned;
  }

  /** Returns the footprint/key id of the signing key. */
  public int getFootprint() {
    return footprint;
  }

  /** Returns the owner of the signing key */
  public Name getSigner() {
    return signer;
  }

  /** Returns the binary data representing the signature */
  public byte[] getSignature() {
    return signature;
  }

  void setSignature(byte[] signature) {
    this.signature = signature;
  }

  @Override
  protected void rrToWire(DNSOutput out, Compression c, boolean canonical) {
    out.writeU16(covered);
    out.writeU8(alg);
    out.writeU8(labels);
    out.writeU32(origttl);
    out.writeU32(expire.getEpochSecond());
    out.writeU32(timeSigned.getEpochSecond());
    out.writeU16(footprint);
    signer.toWire(out, null, canonical);
    out.writeByteArray(signature);
  }
}

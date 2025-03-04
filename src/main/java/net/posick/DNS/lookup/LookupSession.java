// SPDX-License-Identifier: BSD-3-Clause
package net.posick.DNS.lookup;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.posick.DNS.AAAARecord;
import net.posick.DNS.ARecord;
import net.posick.DNS.CNAMERecord;
import net.posick.DNS.Cache;
import net.posick.DNS.Credibility;
import net.posick.DNS.DClass;
import net.posick.DNS.DNAMERecord;
import net.posick.DNS.ExtendedResolver;
import net.posick.DNS.Lookup;
import net.posick.DNS.Message;
import net.posick.DNS.Name;
import net.posick.DNS.NameTooLongException;
import net.posick.DNS.RRset;
import net.posick.DNS.Rcode;
import net.posick.DNS.Record;
import net.posick.DNS.Resolver;
import net.posick.DNS.ResolverConfig;
import net.posick.DNS.Section;
import net.posick.DNS.SetResponse;
import net.posick.DNS.SimpleResolver;
import net.posick.DNS.Type;
import net.posick.DNS.hosts.HostsFileParser;
import net.posick.DNS.lookup.InvalidZoneDataException;
import net.posick.DNS.lookup.LookupFailedException;
import net.posick.DNS.lookup.LookupResult;
import net.posick.DNS.lookup.NoSuchDomainException;
import net.posick.DNS.lookup.NoSuchRRSetException;
import net.posick.DNS.lookup.RedirectOverflowException;
import net.posick.DNS.lookup.ServerFailedException;

/**
 * LookupSession provides facilities to make DNS Queries. A LookupSession is intended to be long
 * lived, and it's behaviour can be configured using the properties of the {@link
 * LookupSessionBuilder} instance returned by {@link #builder()}.
 */
@Slf4j
public class LookupSession {
  public static final int DEFAULT_MAX_ITERATIONS = 16;
  public static final int DEFAULT_NDOTS = 1;

  private final Resolver resolver;
  private final int maxRedirects;
  private final int ndots;
  private final List<Name> searchPath;
  private final boolean cycleResults;
  private final Map<Integer, Cache> caches;
  private final HostsFileParser hostsFileParser;
  private final Executor executor;

  private LookupSession(
      @NonNull Resolver resolver,
      int maxRedirects,
      int ndots,
      List<Name> searchPath,
      boolean cycleResults,
      List<Cache> caches,
      HostsFileParser hostsFileParser,
      Executor executor) {
    this.resolver = resolver;
    this.maxRedirects = maxRedirects;
    this.ndots = ndots;
    this.searchPath = searchPath;
    this.cycleResults = cycleResults;
    this.caches =
        caches == null
            ? Collections.emptyMap()
            : caches.stream().collect(Collectors.toMap(Cache::getDClass, e -> e));
    this.hostsFileParser = hostsFileParser;
    this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
  }

  /**
   * A builder for {@link LookupSession} instances. An instance of this class is obtained by calling
   * {@link LookupSession#builder()} and configured using the methods with names corresponding to
   * the different properties. Once fully configured, a {@link LookupSession} instance is obtained
   * by calling {@link LookupSessionBuilder#build()} on the builder instance.
   */
  @ToString
  public static class LookupSessionBuilder {
    private Resolver resolver;
    private int maxRedirects;
    private int ndots;
    private List<Name> searchPath;
    private boolean cycleResults;
    private List<Cache> caches;
    private HostsFileParser hostsFileParser;
    private Executor executor;

    private LookupSessionBuilder() {}

    /**
     * The {@link Resolver} to use to look up records.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder resolver(@NonNull Resolver resolver) {
      this.resolver = resolver;
      return this;
    }

    /**
     * The maximum number of CNAME or DNAME redirects allowed before lookups will fail with {@link
     * RedirectOverflowException}. Defaults to {@value
     * net.posick.DNS.lookup.LookupSession#DEFAULT_MAX_ITERATIONS}.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder maxRedirects(int maxRedirects) {
      this.maxRedirects = maxRedirects;
      return this;
    }

    /**
     * The threshold for the number of dots which must appear in a name before it is considered
     * absolute. The default is {@value net.posick.DNS.lookup.LookupSession#DEFAULT_NDOTS}, meaning
     * that if there are any dots in a name, the name will be tried first as an absolute name.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder ndots(int ndots) {
      this.ndots = ndots;
      return this;
    }

    /**
     * Configures the search path used to look up relative names with less than ndots dots.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder searchPath(Name searchPath) {
      if (this.searchPath == null) {
        this.searchPath = new ArrayList<>();
      }
      this.searchPath.add(searchPath);
      return this;
    }

    /**
     * Configures the search path used to look up relative names with less than ndots dots.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder searchPath(Collection<? extends Name> searchPath) {
      if (this.searchPath == null) {
        this.searchPath = new ArrayList<>();
      }
      this.searchPath.addAll(searchPath);
      return this;
    }

    /**
     * Removes all search paths.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder clearSearchPath() {
      if (this.searchPath != null) {
        this.searchPath.clear();
      }
      return this;
    }

    /**
     * If set to {@code true}, cached results with multiple records will be returned with the
     * starting point shifted one step per request.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder cycleResults(boolean cycleResults) {
      this.cycleResults = cycleResults;
      return this;
    }

    /**
     * Configures the local hosts database file parser to use within this session.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder hostsFileParser(HostsFileParser hostsFileParser) {
      this.hostsFileParser = hostsFileParser;
      return this;
    }

    /**
     * The executor to use when running lookups.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder executor(Executor executor) {
      this.executor = executor;
      return this;
    }

    /**
     * Enable querying the local hosts database using the system defaults.
     *
     * @see HostsFileParser
     * @return {@code this}.
     */
    public LookupSessionBuilder defaultHostsFileParser() {
      hostsFileParser = new HostsFileParser();
      return this;
    }

    /**
     * Enable caching using the supplied cache. An existing {@link Cache} for the same class will be
     * replaced.
     *
     * @see Cache
     * @return {@code this}.
     */
    public LookupSessionBuilder cache(@NonNull Cache cache) {
      if (caches == null) {
        caches = new ArrayList<>(1);
      }
      for (Cache c : caches) {
        if (c.getDClass() == cache.getDClass()) {
          caches.remove(c);
          break;
        }
      }
      caches.add(cache);
      return this;
    }

    /**
     * Enable caching using the supplied caches. Existing {@link Cache}s for the same class will be
     * replaced.
     *
     * @see Cache
     * @return {@code this}.
     */
    public LookupSessionBuilder caches(@NonNull Collection<Cache> caches) {
      caches.forEach(this::cache);
      return this;
    }

    /**
     * Disables using a cache for lookups.
     *
     * @return {@code this}.
     */
    public LookupSessionBuilder clearCaches() {
      if (caches != null) {
        caches.clear();
      }
      return this;
    }

    /**
     * Enable caching using the supplied cache for the given class.
     *
     * @param dclass unused
     * @deprecated use {@link #cache(Cache)}, the {@link Cache} already provides the class.
     * @see Cache
     * @return {@code this}.
     */
    @Deprecated
    public LookupSessionBuilder cache(@NonNull Integer dclass, @NonNull Cache cache) {
      cache(cache);
      return this;
    }

    /**
     * Enable caching using the supplied caches.
     *
     * @param caches unused
     * @deprecated use {@link #cache(Cache)} or {@link #caches(Collection)}, the {@link Cache}
     *     already provides the class.
     * @see Cache
     * @return {@code this}.
     */
    @Deprecated
    public LookupSessionBuilder caches(@NonNull Map<Integer, Cache> caches) {
      return caches(caches.values());
    }

    /** Create an instance of {@link LookupSession} configured by this builder. */
    public LookupSession build() {
      // note that this transform is idempotent, as concatenating an already absolute Name with root
      // is a noop.
      if (searchPath != null) {
        searchPath =
            searchPath.stream()
                .map(
                    name -> {
                      try {
                        return Name.concatenate(name, Name.root);
                      } catch (NameTooLongException e) {
                        throw new IllegalArgumentException("Search path name too long");
                      }
                    })
                .collect(Collectors.toCollection(ArrayList::new));
      } else {
        searchPath = Collections.emptyList();
      }

      return new LookupSession(
          resolver,
          maxRedirects,
          ndots,
          searchPath,
          cycleResults,
          caches,
          hostsFileParser,
          executor);
    }
  }

  /**
   * Returns an empty {@link LookupSessionBuilder} instance. See {@link #defaultBuilder()} for a
   * builder initialized with defaults.
   */
  public static LookupSessionBuilder builder() {
    LookupSessionBuilder builder = new LookupSessionBuilder();
    builder.maxRedirects = DEFAULT_MAX_ITERATIONS;
    builder.ndots = DEFAULT_NDOTS;
    return builder;
  }

  /**
   * Returns a {@link LookupSessionBuilder} instance initialized with defaults.
   *
   * <ul>
   *   <li>Resolver: an {@link ExtendedResolver} initialized with the system's default
   *       DNS servers as determined by {@link ResolverConfig}.
   *   <li>ndots: as determined by {@link ResolverConfig}.
   *   <li>Cache: A cache for the {@code IN} class is installed.
   *   <li>Hosts: The local host database file is used.
   * </ul>
   */
  public static LookupSessionBuilder defaultBuilder() {
    return builder()
        .resolver(
            new ExtendedResolver(
                ResolverConfig.getCurrentConfig().servers().stream()
                    .map(SimpleResolver::new)
                    .collect(Collectors.toList())))
        .ndots(ResolverConfig.getCurrentConfig().ndots())
        .cache(new Cache(DClass.IN))
        .defaultHostsFileParser();
  }

  /**
   * Make an asynchronous lookup of the provided name using the default {@link DClass#IN}.
   *
   * @param name the name to look up.
   * @param type the type to look up, values should correspond to constants in {@link Type}.
   * @return A {@link CompletionStage} what will yield the eventual lookup result.
   */
  public CompletionStage<LookupResult> lookupAsync(Name name, int type) {
    return lookupAsync(name, type, DClass.IN);
  }

  /**
   * Make an asynchronous lookup of the provided name.
   *
   * @param name the name to look up.
   * @param type the type to look up, values should correspond to constants in {@link Type}.
   * @param dclass the class to look up, values should correspond to constants in {@link DClass}.
   * @return A {@link CompletionStage} what will yield the eventual lookup result.
   */
  public CompletionStage<LookupResult> lookupAsync(Name name, int type, int dclass) {
    List<Name> searchNames = expandName(name);
    LookupResult localHostsLookupResult = lookupWithHosts(searchNames, type);
    if (localHostsLookupResult != null) {
      return CompletableFuture.completedFuture(localHostsLookupResult);
    }

    return lookupUntilSuccess(searchNames.iterator(), type, dclass);
  }

  /**
   * Generate a stream of names according to the search path application semantics. The semantics of
   * this is a bit odd, but they are inherited from {@link Lookup}. Note that the stream returned is
   * never empty, as it will at the very least always contain {@code name}.
   */
  List<Name> expandName(Name name) {
    if (name.isAbsolute()) {
      return Collections.singletonList(name);
    }

    List<Name> fromSearchPath =
        searchPath.stream()
            .map(searchSuffix -> safeConcat(name, searchSuffix))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(ArrayList::new));

    if (name.labels() > ndots) {
      fromSearchPath.add(0, safeConcat(name, Name.root));
    } else {
      fromSearchPath.add(safeConcat(name, Name.root));
    }

    return fromSearchPath;
  }

  private static Name safeConcat(Name name, Name suffix) {
    try {
      return Name.concatenate(name, suffix);
    } catch (NameTooLongException e) {
      return null;
    }
  }

  private LookupResult lookupWithHosts(List<Name> names, int type) {
    if (hostsFileParser != null && (type == Type.A || type == Type.AAAA)) {
      try {
        for (Name name : names) {
          Optional<InetAddress> result = hostsFileParser.getAddressForHost(name, type);
          if (result.isPresent()) {
            Record r;
            if (type == Type.A) {
              r = new ARecord(name, DClass.IN, 0, result.get());
            } else {
              r = new AAAARecord(name, DClass.IN, 0, result.get());
            }
            return new LookupResult(Collections.singletonList(r), Collections.emptyList());
          }
        }
      } catch (IOException e) {
        log.debug("Local hosts database parsing failed, ignoring and using resolver", e);
      }
    }

    return null;
  }

  private CompletionStage<LookupResult> lookupUntilSuccess(
      Iterator<Name> names, int type, int dclass) {

    Record query = Record.newRecord(names.next(), type, dclass);
    return lookupWithCache(query, null)
        .thenCompose(answer -> resolveRedirects(answer, query))
        .handle(
            (result, ex) -> {
              Throwable cause = ex == null ? null : ex.getCause();
              if (cause instanceof NoSuchDomainException || cause instanceof NoSuchRRSetException) {
                if (names.hasNext()) {
                  return lookupUntilSuccess(names, type, dclass);
                } else {
                  return completeExceptionally(cause);
                }
              } else if (cause != null) {
                return completeExceptionally(cause);
              } else {
                return CompletableFuture.completedFuture(result);
              }
            })
        .thenCompose(Function.identity());
  }

  private CompletionStage<LookupResult> lookupWithCache(Record queryRecord, List<Name> aliases) {
    return Optional.ofNullable(caches.get(queryRecord.getDClass()))
        .map(c -> c.lookupRecords(queryRecord.getName(), queryRecord.getType(), Credibility.NORMAL))
        .map(setResponse -> setResponseToMessageFuture(setResponse, queryRecord, aliases))
        .orElseGet(() -> lookupWithResolver(queryRecord, aliases));
  }

  private CompletionStage<LookupResult> lookupWithResolver(Record queryRecord, List<Name> aliases) {
    return resolver
        .sendAsync(Message.newQuery(queryRecord), executor)
        .thenApply(this::maybeAddToCache)
        .thenApply(answer -> buildResult(answer, aliases, queryRecord));
  }

  private Message maybeAddToCache(Message message) {
    for (RRset set : message.getSectionRRsets(Section.ANSWER)) {
      if ((set.getType() == Type.CNAME || set.getType() == Type.DNAME) && set.size() != 1) {
        throw new InvalidZoneDataException("Multiple CNAME RRs not allowed, see RFC1034 3.6.2");
      }
    }
    Optional.ofNullable(caches.get(message.getQuestion().getDClass()))
        .ifPresent(cache -> cache.addMessage(message));
    return message;
  }

  private CompletionStage<LookupResult> setResponseToMessageFuture(
      SetResponse setResponse, Record queryRecord, List<Name> aliases) {
    if (setResponse.isNXDOMAIN()) {
      return completeExceptionally(
          new NoSuchDomainException(queryRecord.getName(), queryRecord.getType()));
    }
    if (setResponse.isNXRRSET()) {
      return completeExceptionally(
          new NoSuchRRSetException(queryRecord.getName(), queryRecord.getType()));
    }
    if (setResponse.isSuccessful()) {
      List<Record> records =
          setResponse.answers().stream()
              .flatMap(rrset -> rrset.rrs(cycleResults).stream())
              .collect(Collectors.toList());
      return CompletableFuture.completedFuture(new LookupResult(records, aliases));
    }
    return null;
  }

  private <T extends Throwable> CompletionStage<LookupResult> completeExceptionally(T failure) {
    CompletableFuture<LookupResult> future = new CompletableFuture<>();
    future.completeExceptionally(failure);
    return future;
  }

  private CompletionStage<LookupResult> resolveRedirects(LookupResult response, Record query) {
    return maybeFollowRedirect(response, query, 1);
  }

  private CompletionStage<LookupResult> maybeFollowRedirect(
      LookupResult response, Record query, int redirectCount) {
    if (redirectCount > maxRedirects) {
      throw new RedirectOverflowException(maxRedirects);
    }

    List<Record> records = response.getRecords();
    if (!records.isEmpty()
        && (records.get(0).getType() == Type.CNAME || records.get(0).getType() == Type.DNAME)) {
      return maybeFollowRedirectsInAnswer(response, query, redirectCount);
    } else {
      return CompletableFuture.completedFuture(response);
    }
  }

  private CompletionStage<LookupResult> maybeFollowRedirectsInAnswer(
      LookupResult response, Record query, int redirectCount) {
    List<Name> aliases = new ArrayList<>(response.getAliases());
    List<Record> results = new ArrayList<>();
    Name current = query.getName();
    for (Record r : response.getRecords()) {
      if (redirectCount > maxRedirects) {
        throw new RedirectOverflowException(maxRedirects);
      }

      if (r.getDClass() != query.getDClass()) {
        continue;
      }

      if (r.getType() == Type.CNAME && current.equals(r.getName())) {
        aliases.add(current);
        redirectCount++;
        current = ((CNAMERecord) r).getTarget();
      } else if (r.getType() == Type.DNAME && current.subdomain(r.getName())) {
        aliases.add(current);
        redirectCount++;
        try {
          current = current.fromDNAME((DNAMERecord) r);
        } catch (NameTooLongException e) {
          throw new InvalidZoneDataException(
              "Cannot derive DNAME from " + r + " for " + current, e);
        }
      } else if (r.getType() == query.getType() && current.equals(r.getName())) {
        results.add(r);
      }
    }

    if (!results.isEmpty()) {
      return CompletableFuture.completedFuture(new LookupResult(results, aliases));
    }

    if (redirectCount > maxRedirects) {
      throw new RedirectOverflowException(maxRedirects);
    }

    int finalRedirectCount = redirectCount + 1;
    Record redirectQuery = Record.newRecord(current, query.getType(), query.getDClass());
    return lookupWithCache(redirectQuery, aliases)
        .thenCompose(
            responseFromCache ->
                maybeFollowRedirect(responseFromCache, redirectQuery, finalRedirectCount));
  }

  /** Returns a LookupResult if this response was a non-exceptional empty result, else null. */
  private static LookupResult buildResult(Message answer, List<Name> aliases, Record query) {
    int rcode = answer.getRcode();
    List<Record> answerRecords = answer.getSection(Section.ANSWER);
    if (answerRecords.isEmpty() && rcode != Rcode.NOERROR) {
      switch (rcode) {
        case Rcode.NXDOMAIN:
          throw new NoSuchDomainException(query.getName(), query.getType());
        case Rcode.NXRRSET:
          throw new NoSuchRRSetException(query.getName(), query.getType());
        case Rcode.SERVFAIL:
          throw new ServerFailedException();
        default:
          throw new LookupFailedException(
              String.format("Unknown non-success error code %s", Rcode.string(rcode)));
      }
    }
    return new LookupResult(answerRecords, aliases);
  }
}

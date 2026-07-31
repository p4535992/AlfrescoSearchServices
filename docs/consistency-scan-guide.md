# Index consistency scans

The standalone `alfresco-indexing-trackers` service provides asynchronous consistency scans that replace the operational role of the external [`evasques/alfresco-search-services-inspector-script`](https://github.com/evasques/alfresco-search-services-inspector-script).

## Capabilities

The scanner can detect detailed IDs for missing, duplicated, orphan and error documents. It supports the following strategies:

- `NODE_ID` — repository DBID range;
- `TRANSACTION_ID` — repository transaction range;
- `TRANSACTION_COMMIT_TIME` — epoch-millisecond interval;
- `FULL` — complete repository transaction and ACL scan;
- `AFTS_QUERY` — index-visible AFTS scope;
- `ANCESTOR` — index-visible subtree scope.

`AFTS_QUERY` and `ANCESTOR` start from documents visible in Solr. They can identify orphan or duplicated documents in that scope, but cannot prove that an entirely missing repository node belongs to that AFTS result. Use `NODE_ID`, `TRANSACTION_ID`, `TRANSACTION_COMMIT_TIME` or `FULL` for authoritative missing-node detection.

## Create a scan

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Alfresco-Search-Secret: secret' \
  http://localhost:8085/api/admin/consistency-scans \
  -d '{
    "cores": ["alfresco"],
    "strategy": "TRANSACTION_ID",
    "fromTxId": 100000,
    "toTxId": 200000,
    "maxResults": 50000,
    "includeErrorNodes": true,
    "dryRun": true
  }'
```

The response is `202 Accepted` and contains a `scanId`.

## Poll and read findings

```bash
curl -s http://localhost:8085/api/admin/consistency-scans/<scanId>
curl -s 'http://localhost:8085/api/admin/consistency-scans/<scanId>/findings?type=MISSING_NODE&page=0&size=100'
```

## Export

```bash
curl -OJ 'http://localhost:8085/api/admin/consistency-scans/<scanId>/export?format=json'
curl -OJ 'http://localhost:8085/api/admin/consistency-scans/<scanId>/export?format=csv'
```

## Repair

Repairs are scheduled through the existing `MetadataTracker` and `AclTracker` maintenance queues. The response means `scheduled`, not synchronously completed.

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  http://localhost:8085/api/admin/consistency-scans/<scanId>/repair \
  -d '{
    "types": ["MISSING_NODE", "MISSING_TRANSACTION"],
    "dryRun": true,
    "purgeOrphans": false
  }'
```

Orphan purge is never enabled implicitly. Set both `dryRun: false` and `purgeOrphans: true` to schedule destructive cleanup.

## Persistence and retention

Scan metadata is stored as JSON and findings as JSON Lines under the configured storage directory. Completed scans survive tracker restarts. A scan that was active during a restart is marked `FAILED`.

```yaml
alfresco:
  tracker:
    consistency:
      enabled: true
      storage-path: /var/lib/alfresco-indexing-trackers/consistency
      retention-days: 30
      max-concurrent-scans: 1
      batch-size: 500
      result-page-size-max: 5000
      max-results: 1000000
      worker-threads: 2
```

## Safe migration from the external script

1. Run the old script and a new scan on the same range.
2. Compare IDs, not only counts.
3. Validate missing nodes where the transaction document is still present.
4. Validate multi-core and sharded deployments.
5. Keep repair in dry-run until results are understood.
6. Enable real repair without orphan purge.
7. Enable explicit orphan purge only after manual review.
8. Remove the external script from operating procedures after repeated equivalent results.

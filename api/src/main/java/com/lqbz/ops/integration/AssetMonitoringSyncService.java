package com.lqbz.ops.integration;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetMonitoringSyncService {

    private final AssetRepository assets;

    private final HertzBeatClient hertzBeatClient;

    public AssetMonitoringSyncService(AssetRepository assets, HertzBeatClient hertzBeatClient) {
        this.assets = assets;
        this.hertzBeatClient = hertzBeatClient;
    }

    @Transactional
    public Asset sync(Long assetId) {
        Asset asset = assets.findById(assetId).orElseThrow(() -> new NoSuchElementException("服务不存在"));
        return syncAsset(asset);
    }

    @Transactional
    public SyncAllResult syncAll() {
        List<Asset> allAssets = assets.findAll();
        List<Asset> results = allAssets.stream().map(this::syncAsset).collect(java.util.stream.Collectors.toList());
        int succeeded = (int) results.stream().filter(asset -> "SYNCED".equals(asset.getSyncStatus())).count();
        return new SyncAllResult(results.size(), succeeded, results.size() - succeeded, results);
    }

    private Asset syncAsset(Asset asset) {
        try {
            HertzBeatClient.SyncResult result = hertzBeatClient.sync(asset);
            asset.markSynced(result.monitorId(), result.message());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            asset.markSyncFailed(message);
        }
        return asset;
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class SyncAllResult {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public SyncAllResult(@com.fasterxml.jackson.annotation.JsonProperty("total") int total, @com.fasterxml.jackson.annotation.JsonProperty("succeeded") int succeeded, @com.fasterxml.jackson.annotation.JsonProperty("failed") int failed, @com.fasterxml.jackson.annotation.JsonProperty("assets") List<Asset> assets) {
            this.total = total;
            this.succeeded = succeeded;
            this.failed = failed;
            this.assets = assets;
        }

        private final int total;

        public int total() {
            return this.total;
        }

        private final int succeeded;

        public int succeeded() {
            return this.succeeded;
        }

        private final int failed;

        public int failed() {
            return this.failed;
        }

        private final List<Asset> assets;

        public List<Asset> assets() {
            return this.assets;
        }
    }
}

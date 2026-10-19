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
    Asset asset = assets.findById(assetId).orElseThrow(() -> new NoSuchElementException("资产不存在"));
    return syncAsset(asset);
  }

  @Transactional
  public SyncAllResult syncAll() {
    List<Asset> allAssets = assets.findAll();
    List<Asset> results = allAssets.stream().map(this::syncAsset).toList();
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

  public record SyncAllResult(int total, int succeeded, int failed, List<Asset> assets) {}
}

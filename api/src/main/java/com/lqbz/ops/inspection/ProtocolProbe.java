package com.lqbz.ops.inspection;

import com.lqbz.ops.domain.Asset;
import java.util.List;

public interface ProtocolProbe {

    boolean supports(String type);

    List<ProbeCheck> check(Asset asset, ProbeSettings settings) throws Exception;
}

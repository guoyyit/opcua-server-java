package com.github.guoyyit.opcuaserverjava.opcua.core;

import java.util.List;

/**
 * 浏览结果：基于浏览发起时刻快照生成，续读通过 continuationPoint 完成。
 */
public final class BrowseResult {

    private final List<ReferenceDescription> references;
    private final byte[] continuationPoint;

    public BrowseResult(List<ReferenceDescription> references, byte[] continuationPoint) {
        this.references = references;
        this.continuationPoint = continuationPoint;
    }

    public List<ReferenceDescription> getReferences() {
        return references;
    }

    public byte[] getContinuationPoint() {
        return continuationPoint;
    }

    public boolean hasContinuationPoint() {
        return continuationPoint != null && continuationPoint.length > 0;
    }
}

#import "FaceMeshTopology.h"

static NSString *const TAG = @"FaceMeshTopology";

// ARKit's face mesh ships at 1220 verts / 2304 indices (768 triangles).
// We append 3 synthetic centroid verts (one per closed hole) plus a few
// dozen closure triangles. Padding keeps the bound stable if Apple ever
// revises the topology.
static const NSUInteger kMaxVertices = 1500;
static const NSUInteger kMaxIndices  = 8000;

@interface FaceMeshTopology () {
    // Boundary loops to close (eyes + mouth — head perimeter dropped).
    // Each entry is a heap-allocated int16_t array of vertex indices in
    // traversal order, with a length stored in `_loopLengths`.
    int16_t **_loops;
    NSUInteger *_loopLengths;
    NSUInteger _loopCount;

    BOOL _topologyAnalyzed;
}
@property (nonatomic, assign) simd_float3 *vertexBuffer;
@property (nonatomic, assign) int16_t *indexBuffer;
@property (nonatomic, assign) NSUInteger currentVertexCount;
@property (nonatomic, assign) NSUInteger currentIndexCount;
@end

@implementation FaceMeshTopology

- (instancetype)init {
    self = [super init];
    if (!self) return nil;

    _vertexBuffer = (simd_float3 *)malloc(kMaxVertices * sizeof(simd_float3));
    _indexBuffer  = (int16_t *)malloc(kMaxIndices * sizeof(int16_t));
    _currentVertexCount = 0;
    _currentIndexCount  = 0;
    _topologyAnalyzed = NO;
    _loops = NULL;
    _loopLengths = NULL;
    _loopCount = 0;

    return self;
}

- (void)dealloc {
    if (_vertexBuffer) free(_vertexBuffer);
    if (_indexBuffer)  free(_indexBuffer);
    if (_loops) {
        for (NSUInteger i = 0; i < _loopCount; i++) {
            if (_loops[i]) free(_loops[i]);
        }
        free(_loops);
    }
    if (_loopLengths) free(_loopLengths);
}

- (const simd_float3 *)vertices { return _vertexBuffer; }
- (const int16_t *)indices       { return _indexBuffer; }
- (NSUInteger)vertexCount        { return _currentVertexCount; }
- (NSUInteger)indexCount         { return _currentIndexCount; }

#pragma mark - One-time topology analysis

// Pack two sorted indices into a 32-bit key for hashing.
static inline uint32_t edgeKey(int16_t a, int16_t b) {
    int16_t lo = a < b ? a : b;
    int16_t hi = a < b ? b : a;
    return ((uint32_t)(uint16_t)lo) | (((uint32_t)(uint16_t)hi) << 16);
}

- (void)analyzeTopologyFromGeometry:(ARFaceGeometry *)geometry {
    NSUInteger triCount  = geometry.triangleCount;
    const int16_t *tris  = geometry.triangleIndices;

    // 1. Edge -> count. Use NSMutableDictionary for clarity; 2304 indices /
    //    768 triangles → ~2300 unique edges, trivial cost.
    NSMutableDictionary<NSNumber *, NSNumber *> *edgeCount =
        [NSMutableDictionary dictionaryWithCapacity:triCount * 3];
    for (NSUInteger t = 0; t < triCount; t++) {
        int16_t i0 = tris[t * 3 + 0];
        int16_t i1 = tris[t * 3 + 1];
        int16_t i2 = tris[t * 3 + 2];
        for (int e = 0; e < 3; e++) {
            int16_t a = (e == 0) ? i0 : (e == 1 ? i1 : i2);
            int16_t b = (e == 0) ? i1 : (e == 1 ? i2 : i0);
            NSNumber *key = @(edgeKey(a, b));
            NSNumber *prev = edgeCount[key];
            edgeCount[key] = @(prev.unsignedIntegerValue + 1);
        }
    }

    // 2. Boundary edges = count == 1. Build adjacency: vertex -> the (up to
    //    two) other endpoints it's connected to via boundary edges.
    NSMutableDictionary<NSNumber *, NSMutableArray<NSNumber *> *> *adj =
        [NSMutableDictionary dictionary];
    for (NSNumber *key in edgeCount) {
        if (edgeCount[key].unsignedIntegerValue != 1) continue;
        uint32_t k = key.unsignedIntValue;
        int16_t a = (int16_t)(k & 0xFFFF);
        int16_t b = (int16_t)((k >> 16) & 0xFFFF);
        NSMutableArray<NSNumber *> *aList = adj[@(a)];
        if (!aList) { aList = [NSMutableArray array]; adj[@(a)] = aList; }
        [aList addObject:@(b)];
        NSMutableArray<NSNumber *> *bList = adj[@(b)];
        if (!bList) { bList = [NSMutableArray array]; adj[@(b)] = bList; }
        [bList addObject:@(a)];
    }

    // 3. Trace closed loops. Each boundary vertex appears in exactly 2
    //    boundary edges, so traversal is unambiguous.
    NSMutableArray<NSArray<NSNumber *> *> *allLoops = [NSMutableArray array];
    NSMutableSet<NSNumber *> *visited = [NSMutableSet set];
    for (NSNumber *startKey in adj) {
        if ([visited containsObject:startKey]) continue;
        NSMutableArray<NSNumber *> *loop = [NSMutableArray array];
        NSNumber *prev = nil;
        NSNumber *cur  = startKey;
        while (cur && ![visited containsObject:cur]) {
            [visited addObject:cur];
            [loop addObject:cur];
            // Pick the neighbor that isn't where we came from.
            NSNumber *next = nil;
            for (NSNumber *n in adj[cur]) {
                if (![n isEqual:prev]) { next = n; break; }
            }
            prev = cur;
            cur  = next;
        }
        if (loop.count >= 3) [allLoops addObject:loop];
    }

    // 4. Discard the largest loop (head perimeter). Keep the rest.
    if (allLoops.count <= 1) {
        NSLog(@"%@: only %lu loop(s) detected — closure disabled",
              TAG, (unsigned long)allLoops.count);
        _topologyAnalyzed = YES;
        return;
    }
    [allLoops sortUsingComparator:^NSComparisonResult(NSArray *a, NSArray *b) {
        return [@(b.count) compare:@(a.count)]; // descending
    }];
    NSArray<NSArray<NSNumber *> *> *kept =
        [allLoops subarrayWithRange:NSMakeRange(1, allLoops.count - 1)];

    // Stock ARKit topology: dropped loop is the head perimeter (~56
    // verts), kept loops are the mouth (~36) and the two eyes (~24 each).
    _loopCount   = kept.count;
    _loops       = (int16_t **)malloc(_loopCount * sizeof(int16_t *));
    _loopLengths = (NSUInteger *)malloc(_loopCount * sizeof(NSUInteger));

    for (NSUInteger i = 0; i < _loopCount; i++) {
        NSArray<NSNumber *> *loop = kept[i];
        NSUInteger n = loop.count;
        _loops[i] = (int16_t *)malloc(n * sizeof(int16_t));
        for (NSUInteger j = 0; j < n; j++) {
            _loops[i][j] = loop[j].shortValue;
        }
        _loopLengths[i] = n;
    }
    _topologyAnalyzed = YES;
}

#pragma mark - Per-frame update

- (void)updateWithFace:(ARFaceAnchor *)face {
    if (!face) return;
    ARFaceGeometry *geometry = face.geometry;
    if (!geometry) return;

    if (!_topologyAnalyzed) {
        [self analyzeTopologyFromGeometry:geometry];
    }

    NSUInteger meshVertexCount = geometry.vertexCount;
    NSUInteger meshIndexCount  = geometry.triangleCount * 3;
    if (meshVertexCount == 0 || meshIndexCount == 0) return;

    // Bound check: original verts + one centroid per loop, plus original
    // tris + 3 indices per loop edge.
    NSUInteger closureIndexCount = 0;
    for (NSUInteger i = 0; i < _loopCount; i++) {
        closureIndexCount += _loopLengths[i] * 3;
    }
    NSUInteger totalVertexCount = meshVertexCount + _loopCount;
    NSUInteger totalIndexCount  = meshIndexCount + closureIndexCount;
    if (totalVertexCount > kMaxVertices || totalIndexCount > kMaxIndices) {
        NSLog(@"%@: filled mesh overflow (verts=%lu indices=%lu)",
              TAG,
              (unsigned long)totalVertexCount,
              (unsigned long)totalIndexCount);
        return;
    }

    // 1. Live mesh vertices.
    memcpy(_vertexBuffer, geometry.vertices, meshVertexCount * sizeof(simd_float3));
    NSUInteger vNext = meshVertexCount;

    // 2. Centroid + fan per loop, written into the index buffer first
    //    (the depth-only render with culling disabled doesn't care about
    //    triangle order).
    NSUInteger iNext = 0;
    for (NSUInteger li = 0; li < _loopCount; li++) {
        const int16_t *loop = _loops[li];
        NSUInteger n = _loopLengths[li];

        simd_float3 sum = simd_make_float3(0.0f, 0.0f, 0.0f);
        for (NSUInteger j = 0; j < n; j++) {
            sum += _vertexBuffer[(NSUInteger)loop[j]];
        }
        _vertexBuffer[vNext] = sum / (float)n;
        int16_t centroidIdx  = (int16_t)vNext;
        vNext++;

        for (NSUInteger j = 0; j < n; j++) {
            int16_t a = loop[j];
            int16_t b = loop[(j + 1) % n];
            _indexBuffer[iNext++] = centroidIdx;
            _indexBuffer[iNext++] = a;
            _indexBuffer[iNext++] = b;
        }
    }

    // 3. Append the raw mesh indices.
    memcpy(_indexBuffer + iNext,
           geometry.triangleIndices,
           meshIndexCount * sizeof(int16_t));
    iNext += meshIndexCount;

    _currentVertexCount = vNext;
    _currentIndexCount  = iNext;
}

@end

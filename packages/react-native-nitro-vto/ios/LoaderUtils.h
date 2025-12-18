#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Utility functions for loading assets and remote files.
 */
@interface LoaderUtils : NSObject

/// Load an asset file from the bundle into NSData
+ (nullable NSData *)loadAssetNamed:(NSString *)filename;

/// Load a GLB file from a remote URL with caching
+ (nullable NSData *)loadFromUrl:(NSString *)urlString error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END

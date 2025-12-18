#import "LoaderUtils.h"
#import <CommonCrypto/CommonDigest.h>

static NSString *const TAG = @"LoaderUtils";
static NSString *const CACHE_DIR = @"glb_cache";

@implementation LoaderUtils

#pragma mark - Asset Loading

+ (nullable NSData *)loadAssetNamed:(NSString *)filename {
    // Parse filename components
    NSArray *components = [filename componentsSeparatedByString:@"/"];
    NSString *name = nil;
    NSString *ext = nil;
    NSString *subdirectory = nil;

    if (components.count > 1) {
        NSString *lastComponent = components.lastObject;
        NSArray *nameComponents = [lastComponent componentsSeparatedByString:@"."];
        name = nameComponents.firstObject;
        ext = nameComponents.count > 1 ? nameComponents.lastObject : @"";

        NSMutableArray *pathComponents = [components mutableCopy];
        [pathComponents removeLastObject];
        subdirectory = [pathComponents componentsJoinedByString:@"/"];
    } else {
        NSArray *nameComponents = [filename componentsSeparatedByString:@"."];
        name = nameComponents.firstObject;
        ext = nameComponents.count > 1 ? nameComponents.lastObject : @"";
    }

    // Try to find the resource bundle first (CocoaPods resource_bundles)
    NSBundle *classBundle = [NSBundle bundleForClass:[LoaderUtils class]];
    NSURL *bundleURL = [classBundle URLForResource:@"NitroVtoAssets" withExtension:@"bundle"];
    NSBundle *assetsBundle = bundleURL ? [NSBundle bundleWithURL:bundleURL] : nil;

    // Try different bundle locations
    NSMutableArray *bundles = [NSMutableArray array];
    if (assetsBundle) {
        [bundles addObject:assetsBundle];
    }
    [bundles addObject:classBundle];
    [bundles addObject:[NSBundle mainBundle]];

    for (NSBundle *bundle in bundles) {
        NSURL *url = nil;

        // Try with subdirectory
        if (subdirectory.length > 0) {
            url = [bundle URLForResource:name withExtension:ext subdirectory:subdirectory];
        }

        // Try direct path
        if (!url) {
            url = [bundle URLForResource:name withExtension:ext];
        }

        // Try full filename as resource name
        if (!url) {
            url = [bundle URLForResource:filename withExtension:nil];
        }

        if (url) {
            NSError *error = nil;
            NSData *data = [NSData dataWithContentsOfURL:url options:0 error:&error];
            if (data) {
                NSLog(@"%@: Loaded asset %@ (%lu bytes)", TAG, filename, (unsigned long)data.length);
                return data;
            }
        }
    }

    NSLog(@"%@: Failed to find asset: %@", TAG, filename);
    return nil;
}

#pragma mark - URL Loading

+ (nullable NSData *)loadFromUrl:(NSString *)urlString error:(NSError **)error {
    NSLog(@"%@: Loading GLB from URL: %@", TAG, urlString);

    // Check cache first
    NSURL *cacheFile = [self cacheFileForUrl:urlString];
    if ([[NSFileManager defaultManager] fileExistsAtPath:cacheFile.path]) {
        NSLog(@"%@: Loading from cache: %@", TAG, cacheFile.path);
        return [NSData dataWithContentsOfURL:cacheFile options:0 error:error];
    }

    // Download from URL
    NSURL *url = [NSURL URLWithString:urlString];
    if (!url) {
        if (error) {
            *error = [NSError errorWithDomain:@"LoaderUtils"
                                         code:-1
                                     userInfo:@{NSLocalizedDescriptionKey: @"Invalid URL"}];
        }
        return nil;
    }

    NSData *data = [self downloadFromUrl:url error:error];
    if (!data) {
        return nil;
    }

    // Save to cache
    [self saveToCache:cacheFile data:data];

    return data;
}

#pragma mark - Private Helpers

+ (NSURL *)cacheFileForUrl:(NSString *)urlString {
    NSURL *cacheDir = [self cacheDirectory];
    NSString *filename = [[self hashUrl:urlString] stringByAppendingString:@".glb"];
    return [cacheDir URLByAppendingPathComponent:filename];
}

+ (NSURL *)cacheDirectory {
    NSArray *paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES);
    NSURL *cachesDir = [NSURL fileURLWithPath:paths.firstObject];
    NSURL *glbCacheDir = [cachesDir URLByAppendingPathComponent:CACHE_DIR];

    if (![[NSFileManager defaultManager] fileExistsAtPath:glbCacheDir.path]) {
        [[NSFileManager defaultManager] createDirectoryAtURL:glbCacheDir
                                 withIntermediateDirectories:YES
                                                  attributes:nil
                                                       error:nil];
    }

    return glbCacheDir;
}

+ (NSString *)hashUrl:(NSString *)urlString {
    NSData *data = [urlString dataUsingEncoding:NSUTF8StringEncoding];
    unsigned char hash[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes, (CC_LONG)data.length, hash);

    NSMutableString *hexString = [NSMutableString stringWithCapacity:CC_SHA256_DIGEST_LENGTH * 2];
    for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
        [hexString appendFormat:@"%02x", hash[i]];
    }

    return hexString;
}

+ (nullable NSData *)downloadFromUrl:(NSURL *)url error:(NSError **)error {
    __block NSData *resultData = nil;
    __block NSError *resultError = nil;

    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);

    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.timeoutInterval = 30;

    NSURLSessionDataTask *task = [[NSURLSession sharedSession] dataTaskWithRequest:request
                                                                 completionHandler:^(NSData *data, NSURLResponse *response, NSError *taskError) {
        if (taskError) {
            resultError = taskError;
        } else if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
            NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *)response;
            if (httpResponse.statusCode != 200) {
                resultError = [NSError errorWithDomain:@"LoaderUtils"
                                                  code:httpResponse.statusCode
                                              userInfo:@{NSLocalizedDescriptionKey:
                                                             [NSString stringWithFormat:@"HTTP error code: %ld", (long)httpResponse.statusCode]}];
            } else {
                resultData = data;
            }
        }
        dispatch_semaphore_signal(semaphore);
    }];

    [task resume];
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);

    if (resultError && error) {
        *error = resultError;
        return nil;
    }

    if (!resultData && error) {
        *error = [NSError errorWithDomain:@"LoaderUtils"
                                     code:-1
                                 userInfo:@{NSLocalizedDescriptionKey: @"No data received"}];
        return nil;
    }

    NSLog(@"%@: Downloaded %lu bytes from URL", TAG, (unsigned long)resultData.length);
    return resultData;
}

+ (void)saveToCache:(NSURL *)file data:(NSData *)data {
    NSError *error = nil;
    [data writeToURL:file options:NSDataWritingAtomic error:&error];
    if (error) {
        NSLog(@"%@: Failed to save to cache: %@", TAG, error.localizedDescription);
    } else {
        NSLog(@"%@: Saved %lu bytes to cache: %@", TAG, (unsigned long)data.length, file.path);
    }
}

@end

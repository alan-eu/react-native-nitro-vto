import Foundation
import CryptoKit

enum LoaderUtils {
    private static let tag = "LoaderUtils"
    private static let cacheDir = "glb_cache"

    static func loadModelFromUrl(_ urlString: String) throws -> Data {
        let cacheFile = cacheFileForUrl(urlString)
        if FileManager.default.fileExists(atPath: cacheFile.path) {
            print("\(tag): Loading from cache: \(cacheFile.path)")
            return try Data(contentsOf: cacheFile)
        }

        guard let url = URL(string: urlString) else {
            throw NSError(
                domain: "LoaderUtils",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Invalid URL"]
            )
        }

        print("\(tag): Loading GLB from URL: \(urlString)")
        let data = try download(url: url)

        do {
            try data.write(to: cacheFile, options: .atomic)
            print("\(tag): Saved \(data.count) bytes to cache: \(cacheFile.path)")
        } catch {
            print("\(tag): Failed to save to cache: \(error.localizedDescription)")
        }

        return data
    }

    private static func cacheFileForUrl(_ urlString: String) -> URL {
        let filename = "\(hashUrl(urlString)).glb"
        return cacheDirectory().appendingPathComponent(filename)
    }

    private static func cacheDirectory() -> URL {
        let cacheBase = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let folder = cacheBase.appendingPathComponent(cacheDir, isDirectory: true)
        if !FileManager.default.fileExists(atPath: folder.path) {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        return folder
    }

    private static func hashUrl(_ urlString: String) -> String {
        let digest = SHA256.hash(data: Data(urlString.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func download(url: URL) throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30.0

        let semaphore = DispatchSemaphore(value: 0)
        var resultData: Data?
        var resultError: NSError?

        URLSession.shared.dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }

            if let error {
                resultError = error as NSError
                return
            }

            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
                resultError = NSError(
                    domain: "LoaderUtils",
                    code: httpResponse.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "HTTP error code: \(httpResponse.statusCode)"]
                )
                return
            }

            guard let data else {
                resultError = NSError(
                    domain: "LoaderUtils",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No data received"]
                )
                return
            }

            resultData = data
        }.resume()

        _ = semaphore.wait(timeout: .distantFuture)

        if let resultError {
            throw resultError
        }

        guard let resultData else {
            throw NSError(
                domain: "LoaderUtils",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No data received"]
            )
        }

        print("\(tag): Downloaded \(resultData.count) bytes from URL")
        return resultData
    }
}

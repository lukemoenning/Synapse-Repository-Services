'''
Created on Sep 23, 2011
    # COMMENT: This script is doing one maintenance task: updating .war files in place for a stack.  
    # Will eventually be a function in more complete module for Synapse Administration 
@author: mkellen
'''
import json, synapse.utils, zipfile, urllib

SUPPORTED_ARTIFACT_NAMES = ("portal", "services-repository", "services-authentication")

def _determineDownloadURLForResource(artifactName, version, isSnapshot):
    path = "http://sagebionetworks.artifactoryonline.com/sagebionetworks/"
    return _determineDownloadURL(artifactName, version, isSnapshot, path)
    
def _determineDownloadURLForMetadata(artifactName, version, isSnapshot):
    path = "http://sagebionetworks.artifactoryonline.com/sagebionetworks/api/storage/"
    return _determineDownloadURL(artifactName, version, isSnapshot, path) + ':sample-metadata'

def _determineDownloadURL(artifactName, version, isSnapshot, path):  
    #QUESTION: How do I make this function "private" in Python?  
    if isSnapshot:
        path += "libs-snapshots-local"
    else:
        path += "libs-releases-local"
    path += "/org/sagebionetworks/"
    if (SUPPORTED_ARTIFACT_NAMES.__contains__(artifactName)):
        path += artifactName
    else:
        print("Error: unrecognized module")
        return None
    path += "/"
    path += version
    if isSnapshot:
        path += "-SNAPSHOT"
    path += "/" + artifactName + "-" + version
    if isSnapshot:
        path += "-SNAPSHOT"
    path += ".war" 
    return path

def _findBuildNumber(fileName):
    '''
    Crack open the War and pull the build number out of the manifest file
    '''
    zipFile = zipfile.ZipFile(fileName, 'r')
    mf = zipFile.open('META-INF/MANIFEST.MF', 'r')
    line = mf.readline()
    buildNumber = None
    while (line != None):
        if (line.startswith('Implementation-Build')):
            buildNumber = line.partition(':')[2]
            buildNumber = buildNumber.lstrip().rstrip()
            break
        line = mf.readline()
    zipFile.close()
    return int(buildNumber)

def downloadArtifact(moduleName, version, isSnapshot):
    '''
    Get an artifact from Artifactory and verify download worked via MD5
    '''
    
    # Get the war file and check it's MD5
    warUrl = _determineDownloadURLForResource(moduleName, version, isSnapshot)
    print('preparing to download from ' + warUrl)
    tempFileName = '/temp/' + moduleName +'-'+ version+ '.war'
    urllib.urlretrieve(warUrl, tempFileName)
    md5 = synapse.utils.computeMd5ForFile(tempFileName)

    # Get the metadata and see what actual MD5 should be
    metaUrl = _determineDownloadURLForMetadata(moduleName, version, isSnapshot)
    print('preparing to download from ' + metaUrl)
    metadataFileName = '/temp/' + moduleName + '.json'
    urllib.urlretrieve(metaUrl, metadataFileName)
    file = open(metadataFileName, 'r')
    metadata = json.load(file)
    file.close()
    expectedMD5hexdigest = metadata["checksums"]["md5"]

    if (md5.hexdigest() == expectedMD5hexdigest):
        print("Downloads completed successfully")
    else:
    # TODO: Need to learn right way to handle exceptions in Python
        print("ERROR: MD5 does not match")

    return _findBuildNumber(tempFileName)

def downloadAll(version, isSnapshot):
    # Get all artifacts
    buildNumber = 0
    for artifactName in SUPPORTED_ARTIFACT_NAMES:
        buildNumber = downloadArtifact(artifactName, version, isSnapshot)
        print 'build number '+buildNumber
    return buildNumber
        

#------- UNIT TESTS -----------------
if __name__ == '__main__':
    import unittest

    class TestSageArtifactory(unittest.TestCase):
        
        def test_getArtifact(self):
            #Test happy case with a small artifact
            #TODO: Find a smaller artifact to speed this up
            buildNumber = downloadArtifact('services-authentication', '0.7.9', False)
            self.assertEqual(4655, buildNumber)
            
    unittest.main()
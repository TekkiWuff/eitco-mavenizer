package de.eitco.mavenizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.internal.impl.DefaultRepositorySystem;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.metadata.Metadata.Nature;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.AnalyzerService.MavenUid;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ValueCandidate;

public class MavenRepoChecker {

	private static final Logger LOG = LoggerFactory.getLogger(MavenRepoChecker.class);
	
	Map<MavenUidComponent, Integer> candidatesToCheckConfig = Map.of(
			MavenUidComponent.GROUP_ID, 2,
			MavenUidComponent.ARTIFACT_ID, 2,
			MavenUidComponent.VERSION, 2
			);
	
	public static enum CheckResult {
		MATCH_EXACT_SHA,
		MATCH_EXACT_CLASSNAMES,
		MATCH_SUPERSET_CLASSNAMES,
		NO_MATCH;
	}
	
	private final MavenUid onlineRepoTestJar = new MavenUid("junit", "junit", "4.12");
	
//	private final Path LOCAL_REPO_PATH = Paths.get(System.getProperty("user.home"), ".m2", "repository");
	private final Path TEMP_REPO_PATH =  Paths.get("./temp-m2");
	private final Path TEMP_SETTINGS_FILE = Paths.get("./effective-settings.xml");
	
	private final DefaultServiceLocator resolverServiceLocator;
	private final DefaultRepositorySystem repoSystem;
	private final DefaultRepositorySystemSession repoSystemSession;
	
	private final LocalRepository localTempRepo;
	private final LocalRepositoryManager localTempRepoManager;
	
	private final boolean isWindows;
	
	private final List<RemoteRepository> remoteRepos = Collections.synchronizedList(new ArrayList<>());
	
	private final CompletableFuture<?> onLocalRepoDeleted;
	private final CompletableFuture<?> onSettingsFileWritten;
	private final CompletableFuture<?> onRemoteReposConfigured;
	private final CompletableFuture<?> onOnlineAccessChecked;
	
	
	public MavenRepoChecker() {
		isWindows = MavenUtil.isWindows();
		
		resolverServiceLocator = MavenRepositorySystemUtils.newServiceLocator();
		
		resolverServiceLocator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		resolverServiceLocator.addService(TransporterFactory.class, FileTransporterFactory.class);
		resolverServiceLocator.addService(TransporterFactory.class, HttpTransporterFactory.class);	
//		resolverServiceLocator.addService(TransporterFactory.class, WagonTransporterFactory.class);
		
		repoSystem = new DefaultRepositorySystem();
		repoSystem.initService(resolverServiceLocator);
		
		localTempRepo = new LocalRepository(TEMP_REPO_PATH.toString());
		repoSystemSession = MavenRepositorySystemUtils.newSession();
		localTempRepoManager = repoSystem.newLocalRepositoryManager(repoSystemSession, localTempRepo);
		
		repoSystemSession.setLocalRepositoryManager(localTempRepoManager);
		
		// delete temp repo from previous runs
		onLocalRepoDeleted = deleteLocalTempRepo();
		
		// read settings
		onSettingsFileWritten = writeEffectiveSettingsToFile(TEMP_SETTINGS_FILE);
		onRemoteReposConfigured = onSettingsFileWritten.thenAcceptAsync(__ -> {
			readRepoSettings(TEMP_SETTINGS_FILE);
		});
		
		// test online access
		var readyForDownloads = CompletableFuture.allOf(onLocalRepoDeleted, onRemoteReposConfigured);
		onOnlineAccessChecked = readyForDownloads.thenComposeAsync(__ -> assertOnlineReposReachable());
	}
	
	public CompletableFuture<?> fullyInitialized() {
		return onOnlineAccessChecked;
	}
	
	public Set<MavenUid> selectCandidatesToCheck(Map<MavenUidComponent, List<ValueCandidate>> candidatesMap) {
		Function<ValueCandidate, Boolean> scoreCheck = candidate -> candidate.scoreSum >= 2; 
		
		int maxCount = candidatesToCheckConfig.values().stream().reduce(1, (a, b) -> a * b);// multiply config values
		var result = new LinkedHashSet<MavenUid>(maxCount);// use linked set to make sure highest score combinations are downloaded first
		
		var groupIds = MavenUtil.subList(candidatesMap.get(MavenUidComponent.GROUP_ID), candidatesToCheckConfig.get(MavenUidComponent.GROUP_ID), scoreCheck);
		for (var group : groupIds) {
			
			var artifactIds = MavenUtil.subList(candidatesMap.get(MavenUidComponent.ARTIFACT_ID), candidatesToCheckConfig.get(MavenUidComponent.ARTIFACT_ID), scoreCheck);
			for (var artifact : artifactIds) {
				
				var versions = MavenUtil.subList(candidatesMap.get(MavenUidComponent.VERSION), candidatesToCheckConfig.get(MavenUidComponent.VERSION), scoreCheck);
				if (versions.isEmpty()) {
					result.add(new MavenUid(group.value, artifact.value, null));
				} else {
					for (var version : versions) {
						result.add(new MavenUid(group.value, artifact.value, version.value));
					}
				}
			}
		}
		
		return result;
	}
	
	public Map<MavenUid, CheckResult> checkOnline(Path localJarPath, Set<MavenUid> uidCandidates) {
		var result = new HashMap<MavenUid, CheckResult>();
		String hashLocal = null;
		
		// TODO download should return completablefuture, all futures should be awaited together, then iterate over result files
		fullyInitialized().join();
		
		for (var uid : uidCandidates) {
			if (uid.groupId == null || uid.artifactId == null) {
				throw new IllegalArgumentException();
			}
			if (uid.version != null) {
				var jarResult = downloadJar(uid, false);
				
				if (jarResult.isPresent()) {
					if (hashLocal == null) {
						hashLocal = MavenUtil.sha256(localJarPath.toFile());
					}
					var hashOnline = MavenUtil.sha256(jarResult.get());
					if (hashLocal.equals(hashOnline)) {
						return Map.of(uid, CheckResult.MATCH_EXACT_SHA);
					} else {
						// TODO check classes
						result.put(uid, CheckResult.NO_MATCH);
					}
				} else {
					result.put(uid, CheckResult.NO_MATCH);
				}
			} else {
				// we try to get valid versions and then recurse
				var versions = downloadVersions(uid);
				if (!versions.isEmpty()) {
					return checkOnline(localJarPath, selectVersionCandidates(uid, versions));
				}
			}
		}
		return result;
	}
	
	public List<MavenUid> getSortedBlocking(Map<MavenUid, CompletableFuture<CheckResult>> uidCandidates) {
		throw new UnsupportedOperationException();
	}
	
	public void shutdown() {
		try {
			onRemoteReposConfigured.cancel(true);
			onSettingsFileWritten.get(5, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new RuntimeException(e);
		} 
		catch (InterruptedException | TimeoutException e) {}
	}
	
	private CompletableFuture<Void> deleteLocalTempRepo() {
		return CompletableFuture.runAsync(() -> {
			try {
				FileUtils.deleteDirectory(TEMP_REPO_PATH.toFile());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}
	
	private CompletableFuture<Void> assertOnlineReposReachable() {
		return CompletableFuture.runAsync(() -> {
			try {
				downloadJar(onlineRepoTestJar, true);
				LOG.info("Online repositories are reachable!");
			} catch (Exception e) {
				if (e.getClass().equals(RuntimeException.class) || e.getClass().equals(UncheckedIOException.class)) {
					e = (Exception) e.getCause();
				}
				LOG.error("Online repositories are not reachable! Exiting program.", e);
				System.exit(1);
			}
		});
	}
	
	private void readRepoSettings(Path settingsFile) {
		LOG.debug("Parsing repos from '" + settingsFile.toString() + "'.");
		
		Settings settings = MavenUtil.parse(in -> new SettingsXpp3Reader().read(in), settingsFile.toFile());

		for (var profile : settings.getProfiles()) {
			if (profile.getActivation().isActiveByDefault()) {
				for (var repo : profile.getRepositories()) {
					remoteRepos.add(new RemoteRepository.Builder(repo.getName(), "default", repo.getUrl()).build());
				}
			}
		}
		
		var mavenCentral = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
		remoteRepos.add(mavenCentral);
	}

	private CompletableFuture<Void> writeEffectiveSettingsToFile(Path settingsFile) {
		try {
			var command = new String[] {
			        isWindows ? "mvn.cmd" : "mvn",
			        "help:effective-settings",
			        "-DshowPasswords=true",
			        "-Doutput=\"" + settingsFile.toString() + "\"",
			};
			var processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(true);
			var process = processBuilder.start();
			var onExit = process.onExit();
			
			return onExit.thenAcceptAsync(p -> {
				settingsFile.toFile().deleteOnExit();
				try {
					LOG.debug("Trying to read remote repository configuration via 'mvn help:effective-settings'.");
					var exitValue = p.waitFor();
					if (exitValue != 0) {
						throw new UncheckedIOException(new IOException("Failed to execute 'mvn help:effective-settings'! Is 'mvn' or 'mvn.cmd' available on PATH?"));
					} else {
						LOG.debug("Successfully executed 'mvn help:effective-settings' to get remote repository configuration.");
					}
				} catch (InterruptedException e) {}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} 
	}
	
	private Optional<File> downloadJar(MavenUid uid, boolean throwOnFail) {
		
		var artifact = new DefaultArtifact(uid.groupId, uid.artifactId, "jar", uid.version);
		var request = new ArtifactRequest(artifact, remoteRepos, null);
	    ArtifactResult response;
		try {
			response = repoSystem.resolveArtifact(repoSystemSession, request);
			if (response.isResolved()) {
				LOG.debug("Sucess! Jar found for " + uid + " in repo: " + response.getRepository());
				return Optional.of(response.getArtifact().getFile());
			} else {
				if (throwOnFail) {
					throw new UncheckedIOException(new IOException("Could not resolve artifact '" + artifact + "' online!"));
				}
			}
		} catch (ArtifactResolutionException e) {
			if (throwOnFail) {
				throw new RuntimeException(e);
			}
		}
		
		LOG.debug("Jar not found for " + uid + ".");
		return Optional.empty();
	}
	
	private List<String> downloadVersions(MavenUid uidWithoutVersion) {
	    Metadata metadataId = new DefaultMetadata(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, "maven-metadata.xml", Nature.RELEASE);
	    // TODO we should make sure that maven central is queried if custom remote repo does not find something
	    var request = new MetadataRequest(metadataId, remoteRepos.get(0), null);// TODO which repo is this even
	    
	    List<MetadataResult> responses;
		responses = repoSystem.resolveMetadata(repoSystemSession, List.of(request));
		// TODO we should assert there is only one response since we sent only one request
		// TODO how does this fail when groupId / artifactId don't exist?
		for (var response : responses) {
			if (!response.isResolved()) {
				continue;
			}
			LOG.debug("Sucess! Versions found for " + uidWithoutVersion + " in repo: " + remoteRepos.get(0));// TODO which repo is this even
			var metadataFile = response.getMetadata().getFile();
			try {
				var m = new MetadataXpp3Reader().read(new FileInputStream(metadataFile));
				var versions = m.getVersioning().getVersions();
				return versions;
			} catch (IOException | XmlPullParserException e) {
				throw new RuntimeException(e);
			}
		}
		LOG.debug("Versions not found for " + uidWithoutVersion + ".");
		return List.of();
	}
	
	private Set<MavenUid> selectVersionCandidates(MavenUid uidWithoutVersion, List<String> versions) {
		if (versions.size() == 0) {
			throw new IllegalStateException();
		}
		if (versions.size() == 1) {
			return Set.of(new MavenUid(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, versions.get(0)));
		} else {
			// we just select oldest and newest version because we don't want to download half the world
			var latestVersion = versions.get(0);
			var oldestVersion = versions.get(versions.size() - 1);
			return Set.of(
					new MavenUid(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, latestVersion),
					new MavenUid(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, oldestVersion)
				);
		}
	}
	
//	public void experiment(Map<MavenUidComponent, List<ValueCandidate>> candidatesMap) {
//		
//		
//
//	    
////	    var artifact = new DefaultArtifact("org.apache.camel:camel:pom:3.18.0");
//	    var pomArtifact = new DefaultArtifact("org.apache.camel:camel-core:pom:3.18.0");
//	    var jarArtifact = new DefaultArtifact("org.apache.camel:camel-core:jar:3.18.0");
//
//	    RemoteRepository mavenCentral = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
//	    
//	    {
//		    Metadata metadata = new DefaultMetadata("org.apache.camel", "camel-core", "maven-metadata.xml", Nature.RELEASE);
//		    var request = new MetadataRequest(metadata, mavenCentral, null);
//		    
//		    List<MetadataResult> results;
//			results = system.resolveMetadata(session, List.of(request));
//			for (var result : results) {
//				Metadata x = result.getMetadata();
//				if (!result.isResolved()) {
//					throw new IllegalStateException();
//				}
//				var f = result.getMetadata().getFile();
//				try {
//					var m = new MetadataXpp3Reader().read(new FileInputStream(f));
//					var versions = m.getVersioning().getVersions();
//					for (var v : versions) {
//						System.out.println(v);
//					}
//				} catch (IOException | XmlPullParserException e) {
//					throw new RuntimeException(e);
//				}
//				
//				System.out.println(result);
//				for (var entry : x.getProperties().entrySet()) {
//					System.out.println(entry.getKey() + " - " + entry.getValue());
//				}
//			}
//	    }
	    
//	    {
//		    ArtifactDescriptorRequest dReq = new ArtifactDescriptorRequest(pomArtifact, List.of(mavenCentral), null);
//		    ArtifactDescriptorResult dRes;
//			try {
//				dRes = system.readArtifactDescriptor(session, dReq);
//			} catch (ArtifactDescriptorException e) {
//				throw new RuntimeException(e);
//			}
//			System.out.println();
//		    System.out.println(dRes.getArtifact().toString());
//		    System.out.println(dRes.getProperties());
//		    System.out.println(dRes);
//	    }
//	    {
//		    ArtifactDescriptorRequest dReq = new ArtifactDescriptorRequest(jarArtifact, List.of(mavenCentral), null);
//		    ArtifactDescriptorResult dRes;
//			try {
//				dRes = system.readArtifactDescriptor(session, dReq);
//			} catch (ArtifactDescriptorException e) {
//				throw new RuntimeException(e);
//			}
//			System.out.println();
//			System.out.println(dRes.getArtifact().toString());
//		    System.out.println(dRes.getProperties());
//		    System.out.println(dRes);
//	    }
	    
//	    {
//		    ArtifactRequest request = new ArtifactRequest(pomArtifact, List.of(mavenCentral), null);
//		    ArtifactResult result;
//			try {
//				result = system.resolveArtifact(session, request);
//				if (!result.isResolved()) {
//					throw new IllegalStateException();
//				}
//				System.out.println();
//				System.out.println(result);
//				System.out.println(result.getArtifact().getFile().getName());
//			} catch (ArtifactResolutionException e) {
//				throw new RuntimeException(e);
//			}
//	    }
//	    
//	    throw new IllegalStateException();
//	}
}

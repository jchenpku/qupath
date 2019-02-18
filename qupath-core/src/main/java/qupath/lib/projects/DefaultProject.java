/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.projects;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.URLTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.RotatedImageServer;
import qupath.lib.io.PathIO;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * Data structure to store multiple images, relating these to a file system.
 * 
 * @author Pete Bankhead
 *
 * @param <BufferedImage>
 */
public class DefaultProject implements Project<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(DefaultProject.class);
	
	private String version = "0.2";

	/**
	 * Base directory.
	 */
	private File dirBase;

	/**
	 * Project file.
	 */
	private File file;
	
	/**
	 * Project name.
	 */
	private String name = null;
	
	/**
	 * Default classifications.
	 */
	private List<PathClass> pathClasses = new ArrayList<>();
	
	private boolean maskNames = false;
	
	private AtomicLong count = new AtomicLong(0L);
	
	private Map<String, DefaultProjectImageEntry> images = new LinkedHashMap<>();
	
	private long creationTimestamp;
	private long modificationTimestamp;
	
	DefaultProject(final File file) {
		this.file = file;
		if (file.isDirectory()) {
			this.dirBase = file;
			this.file = getUniqueFile(dirBase, "project", ".qpproj");
		} else
			this.dirBase = file.getParentFile();
		creationTimestamp = System.currentTimeMillis();
		modificationTimestamp = System.currentTimeMillis();
	}
	
	/**
	 * Get a File with a unique name, derived by appending an integer to the name if necessary.
	 * <p>
	 * The result will be {@code new File(dir, name+ext)} if possible, or 
	 * {@code new File(dir, name+"-"+count+ext)} where {@code count} is the lowest positive integer 
	 * necessary to give a unique file.
	 * 
	 * @param dir
	 * @param name
	 * @param ext
	 * @return
	 */
	synchronized static File getUniqueFile(File dir, String name, String ext) {
		if (!ext.startsWith("."))
			ext = "." + ext;
		File file = new File(dir, name + ext);
		int count = 0;
		while (file.exists()) {
			count++;
			file = new File(dir, name + "-" + count + ext);
		}
		return file;
	}
	
	
	static DefaultProject loadFromFile(File file) {
		return new DefaultProject(file);
	}
	
	
	/**
	 * Get an unmodifiable list representing the <code>PathClass</code>es associated with this project.
	 * @return
	 */
	public List<PathClass> getPathClasses() {
		return Collections.unmodifiableList(pathClasses);
	}
	
	/**
	 * Update the available PathClasses.
	 * 
	 * @param pathClasses
	 * @return <code>true</code> if the stored values changed, false otherwise.
	 */
	public boolean setPathClasses(Collection<? extends PathClass> pathClasses) {
		if (this.pathClasses.size() == pathClasses.size() && this.pathClasses.containsAll(pathClasses))
			return false;
		this.pathClasses.clear();
		this.pathClasses.addAll(pathClasses);
		return true;
	}

	public boolean addImage(final ProjectImageEntry<BufferedImage> entry) {
		return addImage(new DefaultProjectImageEntry(entry.getServerPath(), entry.getOriginalImageName(), entry.getDescription(), entry.getMetadataMap()));
	}
	
	
	private boolean addImage(final DefaultProjectImageEntry entry) {
		if (images.containsKey(entry.getServerPath()))
			return false;
		images.put(entry.getServerPath(), entry);
		return true;
	}
	
	public File getFile() {
		return file;
	}
	
	public File getBaseDirectory() {
		return dirBase;
	}
	
	public Path getBasePath() {
		return getBaseDirectory().toPath();
	}
	
	public boolean addAllImages(final Collection<ProjectImageEntry<BufferedImage>> entries) {
		boolean changes = false;
		for (ProjectImageEntry<BufferedImage> entry : entries)
			changes = addImage(entry) | changes;
		return changes;
	}
	
	public int size() {
		return images.size();
	}

	public boolean isEmpty() {
		return images.isEmpty();
	}
	
	
	public boolean addImagesForServer(final ImageServer<BufferedImage> server) {
		return addImagesForServer(server, true);
	}
	

	public boolean addImagesForServer(final ImageServer<BufferedImage> server, boolean includeSubImages) {
		boolean changes = false;
		if (addImage(new DefaultProjectImageEntry(server.getPath(), server.getDisplayedImageName(), null))) {
			// TODO: Write thumbnail
			changes = true;
		}

		if (includeSubImages) {
			List<String> subImages = server.getSubImageList();
			for (String name : subImages) {
				// TODO: Consider limiting classes
//				try (var server2 = ImageServerProvider.buildServer(server.getSubImagePath(name), BufferedImage.class, server.getClass().getName())) {
				try (var server2 = ImageServerProvider.buildServer(server.getSubImagePath(name), BufferedImage.class)) {
					changes = changes | addImagesForServer(server2, false);
				} catch (Exception e) {
					logger.error("Error attempting to add sub-image " + name, e);
				}
				// The sub image name might be the same across images, we should append the server displayed name to it, just to make sure it is unique
//				changes = changes | addImage(new DefaultProjectImageEntry(server.getSubImagePath(name), name, null));
//				changes = changes | addImage(new DefaultProjectImageEntry(server.getSubImagePath(name), server.getDisplayedImageName()+" ("+name+")", null));
			}
		}
		return changes;
	}
	
	
	public ProjectImageEntry<BufferedImage> getImageEntry(final String path) {
		return images.get(path);
	}

	public boolean addImage(final String path) {
		try {
			ImageServer<BufferedImage> server = ImageServerProvider.buildServer(path, BufferedImage.class);
			boolean changes = addImagesForServer(server);
			server.close();
			return changes;
		} catch (Exception e) {
			logger.error("Error adding image: {} ({})", path, e.getLocalizedMessage());
			return false;
		}
	}
	
	public void removeImage(final ProjectImageEntry<?> entry) {
		removeImage(entry.getServerPath());
	}

	public void removeAllImages(final Collection<ProjectImageEntry<BufferedImage>> entries) {
		for (ProjectImageEntry<BufferedImage> entry : entries)
			removeImage(entry);
	}
	
	public void removeImage(final String path) {
		images.remove(path);
	}
	
	public void syncChanges() throws IOException {
//		if (file.isDirectory())
//			file = new File(dirBase, "project.qpproj");
//		var json = new GsonBuilder().setLenient().setPrettyPrinting().create().toJson(this);
//		Files.writeString(file.toPath(), json);
		logger.warn("Syncing project not yet implemented!");
	}
	
	/**
	 * Try syncing changes quietly, logging any exceptions.
	 */
	private void requestSyncQuietly() {
		try {
			syncChanges();
		} catch (IOException e) {
			logger.error("Error syncing project changes", e);
		}
	}
	
	@Override
	public boolean getMaskImageNames() {
		return maskNames;
	}
	
	@Override
	public void setMaskImageNames(boolean maskNames) {
		this.maskNames = maskNames;
	}

	/**
	 * Get a list of image entries for the project.
	 * 
	 * @return
	 */
	public List<ProjectImageEntry<BufferedImage>> getImageList() {
		List<ProjectImageEntry<BufferedImage>> list = new ArrayList<>(images.values());
//		list.sort(ImageEntryComparator.instance);
		return list;
	}
	
	public ImageServer<BufferedImage> buildServer(final ProjectImageEntry<BufferedImage> entry) {
		return ImageServerProvider.buildServer(entry.getServerPath(), BufferedImage.class);
	}
	
	
	public String getName() {
		if (name != null)
			return name;
		if (dirBase == null || !dirBase.isDirectory()) {
			return "(Project directory missing)";
		}
		if (file != null && file.exists() && file != dirBase) {
			return dirBase.getName() + "/" + file.getName();
		}
		return dirBase.getName();
	}
	
	@Override
	public String toString() {
		return "Project: " + getName();
	}
	
	public long getCreationTimestamp() {
		return creationTimestamp;
	}
	
	public long getModificationTimestamp() {
		return modificationTimestamp;
	}
	
	
	
	/**
	 * Class to represent an image entry within a project.
	 * 
	 * This stores the path to the image, and some optional metadata.
	 * 
	 * @author Pete Bankhead
	 *
	 * @param <T> Depends upon the project used; typically BufferedImage for QuPath
	 */
	class DefaultProjectImageEntry implements ProjectImageEntry<BufferedImage> {

		private transient String cleanedPath = null;
		
		private String uniqueName;
		
		private String serverPath;
		private URI uri;
		
		private String randomizedName = UUID.randomUUID().toString();
		
		private String imageName;
		private String description;

		private Map<String, String> metadata = new HashMap<>();
		
		DefaultProjectImageEntry(final String serverPath, final String imageName, final String description, final Map<String, String> metadataMap) {
//			this.project = project;
			this.serverPath = serverPath;
			
			// TODO: Check if this is a remotely acceptable way to achieve relative pathnames!  I suspect it is not really...
			try {
				File file = new File(serverPath);
				if (file.exists())
					uri = file.toURI();
				else
					uri = new URI(serverPath);
			} catch (URISyntaxException e) {
				logger.error("Not a valid URI!", e);
			}
			
			String projectPath = getBaseDirectory().getAbsolutePath();
			if (this.serverPath.startsWith(projectPath))
				this.serverPath = "{$PROJECT_DIR}" + this.serverPath.substring(projectPath.length());
			
			if (imageName == null) {
				if (URLTools.checkURL(serverPath))
					this.imageName = URLTools.getNameFromBaseURL(serverPath);
				else
					this.imageName = new File(serverPath).getName();
			} else
				this.imageName = imageName;
			
			if (description != null)
				setDescription(description);
			
			if (metadataMap != null)
				metadata.putAll(metadataMap);		
		}
		
		DefaultProjectImageEntry(final String serverPath, final String imageName, final Map<String, String> metadataMap) {
			this(serverPath, imageName, null, metadataMap);
		}
		
		
		/**
		 * Get a name that uniquely identifies the image within this project.
		 * 
		 * @return
		 */
		public String getUniqueName() {
			if (uniqueName == null) {
				uniqueName = UUID.randomUUID().toString();
			}
			return uniqueName;
		}
		
		
		/**
		 * Get the path used to represent this image, which can be used to construct an <code>ImageServer</code>.
		 * 
		 * Note that this may have been cleaned up.
		 * 
		 * @see #getStoredServerPath
		 * 
		 * @return
		 */
		public String getServerPath() {
//			return serverPath;
			return uri.toString();
		}

		/**
		 * Get a name that may be used for this entry.
		 * 
		 * This may be derived automatically from the server path, or set explicitly to be something else.
		 * 
		 * @return
		 */
		public String getImageName() {
			if (maskNames)
				return randomizedName;
			return imageName;
		}
		
		@Override
		public String getOriginalImageName() {
			return imageName;
		}

		@Override
		public String toString() {
			String s = getImageName();
			if (!metadata.isEmpty())
				s += " - " + getMetadataSummaryString();
			return s;
		}
		
		/**
		 * Same as <code>getServerPath</code>.
		 * 
		 * @see #getServerPath
		 * 
		 * @return
		 */
		public String getStoredServerPath() {
			return getServerPath();
		}
		
		public void setImageName(String name) {
			this.imageName = name;
		}
		
		/**
		 * Check if this image entry refers to a specified image according to its path.
		 * 
		 * @param serverPath
		 * @return <code>true</code> if the path is a match, <code>false</code> otherwise.
		 */
		public boolean sameServerPath(final String serverPath) {
			return getServerPath().equals(serverPath);
		}
		
		/**
		 * Remove a metadata value.
		 * 
		 * @param key
		 * @return
		 */
		public String removeMetadataValue(final String key) {
			return metadata.remove(key);
		}
		
		/**
		 * Request a metadata value.
		 * Note that this may return <code>null</code>.
		 * 
		 * @param key
		 * @return
		 */
		public String getMetadataValue(final String key) {
			return metadata.get(key);
		}

		/**
		 * Store a metadata value.
		 * This is intended as storage of short key-value pairs.
		 * Extended text should be stored under <code>setDescription</code>.
		 * 
		 * @param key
		 * @param value
		 * @return
		 */
		public String putMetadataValue(final String key, final String value) {
			return metadata.put(key, value);
		}
		
		/**
		 * Check if a metadata value is present for a specified key.
		 * 
		 * @param key
		 * @return <code>true</code> if <code>getDescription()</code> does not return null or an empty string, <code>false</code> otherwise.
		 */
		public boolean containsMetadata(final String key) {
			return metadata.containsKey(key);
		}
		
		/**
		 * Get a description; this is free text describing the image.
		 * @return
		 */
		public String getDescription() {
			return description;
		}
		
		/**
		 * Set the description.
		 * 
		 * @see #getDescription
		 * @param description
		 */
		public void setDescription(final String description) {
			this.description = description;
		}
		
		/**
		 * Check if a description is present.
		 * 
		 * @return <code>true</code> if <code>getDescription()</code> does not return null or an empty string, <code>false</code> otherwise.
		 */
		public boolean hasDescription() {
			return this.description != null && !this.description.isEmpty();
		}
		
		/**
		 * Remove all metadata.
		 */
		public void clearMetadata() {
			this.metadata.clear();
		}
		
		/**
		 * Get an unmodifiable view of the underlying metadata map.
		 * 
		 * @return
		 */
		public Map<String, String> getMetadataMap() {
			return Collections.unmodifiableMap(metadata);
		}
		
		/**
		 * Get an unmodifiable collection of the metadata map's keys.
		 * 
		 * @return
		 */
		public Collection<String> getMetadataKeys() {
			return Collections.unmodifiableSet(metadata.keySet());
		}
		
		
		public ImageServer<BufferedImage> buildImageServer() {
			String value = metadata.getOrDefault("rotate180", "false");
			boolean rotate180 = value.toLowerCase().equals("true");
			var server = ImageServerProvider.buildServer(getServerPath(), BufferedImage.class);
			if (rotate180)
				return new RotatedImageServer(server, RotatedImageServer.Rotation.ROTATE_180);
			return server;
		}
		
		private Path getEntryPath() {
			return Paths.get(getBasePath().toString(), "data", getUniqueName());
		}
		
		private Path getImageDataPath() {
			return Paths.get(getEntryPath().toString(), "data.qpdata");
		}
		

		@Override
		public ImageData<BufferedImage> readImageData() {
			Path path = getImageDataPath();
			var server = buildImageServer();
			if (server == null)
				return null;
			if (Files.exists(path)) {
				try (var stream = Files.newInputStream(path)) {
					return PathIO.readImageData(stream, null, server, BufferedImage.class);
				} catch (IOException e) {
					logger.error("Error reading image data from " + path, e);
				}
			}
			return new ImageData<>(server);
		}

		@Override
		public void saveImageData(ImageData<BufferedImage> imageData) {
			// TODO: Switch to use paths...
			File file = getImageDataPath().toFile();
			if (!file.getParentFile().exists())
				file.getParentFile().mkdirs();
			PathIO.writeImageData(file, imageData);
		}

		@Override
		public boolean hasImageData() {
			return Files.exists(getImageDataPath());
		}
		
		public PathObjectHierarchy readHierarchy() {
			// TODO: Switch to use paths...
			File file = getImageDataPath().toFile();
			if (file.exists())
				return PathIO.readHierarchy(file);
			return new PathObjectHierarchy();
		}
		
		
		@Override
		public String getSummary() {
			StringBuilder sb = new StringBuilder();
			sb.append(getImageName()).append("\n\n");
			if (!getMetadataMap().isEmpty()) {
				for (Entry<String, String> mapEntry : getMetadataMap().entrySet()) {
					sb.append(mapEntry.getKey()).append(":\t").append(mapEntry.getValue()).append("\n");
				}
				sb.append("\n");
			}

			File file = getImageDataPath().toFile();
			if (file != null && file.exists()) {
				double sizeMB = file.length() / 1024.0 / 1024.0;
				sb.append(String.format("Data file:\t%.2f MB", sizeMB)).append("\n");
//				sb.append("Modified:\t").append(dateFormat.format(new Date(file.lastModified())));
			} else
				sb.append("No data file");
			return sb.toString();
		}
		
		
	}
	
}

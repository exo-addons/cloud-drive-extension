/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.clouddrive.cmis.ecms.viewer.storage;

import org.artofsolving.jodconverter.office.OfficeException;
import org.exoplatform.clouddrive.BaseCloudDriveListener;
import org.exoplatform.clouddrive.CloudDrive;
import org.exoplatform.clouddrive.CloudDriveEvent;
import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFile;
import org.exoplatform.clouddrive.DriveRemovedException;
import org.exoplatform.clouddrive.cmis.ContentReader;
import org.exoplatform.clouddrive.cmis.JCRLocalCMISDrive;
import org.exoplatform.clouddrive.jcr.JCRLocalCloudDrive;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.cache.ExoCache;
import org.exoplatform.services.cms.jodconverter.JodConverterService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.pdfviewer.PDFViewerService;
import org.exoplatform.wcm.connector.viewer.PDFViewerRESTService;
import org.icepdf.core.exceptions.PDFException;
import org.icepdf.core.exceptions.PDFSecurityException;
import org.icepdf.core.pobjects.Document;
import org.icepdf.core.pobjects.PInfo;
import org.icepdf.core.pobjects.Page;
import org.icepdf.core.pobjects.Stream;
import org.icepdf.core.util.GraphicsRenderingHints;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

/**
 * Convert cloud file to PDF file a store it in temporary local file on the file system. This file can be used
 * for remote file representation in eXo Platform. <br>
 * This class build on an idea of ECMS {@link org.exoplatform.services.pdfviewer.PDFViewerService} but uses
 * {@link CloudFile} instead of JCR {@link Node} for file data.<br>
 * This service uses {@link ExoCache} as a weak storage of spooled locally cloud files. The storage will be
 * cleaned if file/drive will be removed or the cache will be evicted.<br>
 * Local files will be stored in JVM temporary folder in a tree hiearchy:
 * repository/workspace/username/driveTitle/fileId.<br>
 * If remote file is not in PDF format it will be attempted to convert it to the PDF by
 * {@link JodConverterService}. <br>
 * 
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: PDFViewerStorage.java 00000 Nov 25, 2014 pnedonosko $
 * 
 */
public class PDFViewerStorage {

  private static final Log   LOG                 = ExoLogger.getLogger(PDFViewerStorage.class);

  public static final int    MAX_FILENAME_LENGTH = 180;

  public static final long   FILE_LIVE_TIME      = 12 * 60 * 60000;                            // 12hrs

  public static final String PAGE_IMAGE_TYPE     = "image/png";

  public static final String PDF_TYPE            = "application/pdf";

  public static final String PAGE_IMAGE_EXT      = ".png";

  public static final String PDF_EXT             = ".pdf";

  protected class FileKey implements Serializable {

    private static final long serialVersionUID = -1075842770973938557L;

    protected final String    repository, workspace, username, driveName, fileId;

    protected final int       hashCode;

    protected FileKey(String repository, String workspace, String username, String driveName, String fileId) {
      this.repository = repository;
      this.workspace = workspace;
      this.username = username;
      this.driveName = driveName;
      this.fileId = fileId;

      int hc = 1;
      hc = hc * 31 + repository.hashCode();
      hc = hc * 31 + workspace.hashCode();
      hc = hc * 31 + username.hashCode();
      hc = hc * 31 + driveName.hashCode();
      hc = hc * 31 + fileId.hashCode();
      this.hashCode = hc;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      return hashCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
      if (obj != null && obj instanceof FileKey) {
        FileKey other = (FileKey) obj;
        return repository.equals(other.repository) && workspace.equals(other.workspace)
            && username.equals(other.username) && driveName.equals(other.driveName)
            && fileId.equals(other.fileId);
      }
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      StringBuilder key = new StringBuilder();
      key.append(repository);
      key.append(File.separatorChar);
      key.append(workspace);
      key.append(File.separatorChar);
      key.append(username);
      key.append(File.separatorChar);
      key.append(driveName);
      key.append(File.separatorChar);
      key.append(fileId);
      return key.toString();
    }
  }

  public class PDFFile implements ContentReader {

    protected class PageKey {

      protected final Integer page;

      protected final Float   rotation;

      protected final Float   scale;

      protected final int     hashCode;

      protected PageKey(Integer page, Float rotation, Float scale) {
        this.page = page;
        this.rotation = rotation;
        this.scale = scale;

        int hc = 1;
        hc = hc * 31 + page.hashCode();
        hc = hc * 31 + rotation.hashCode();
        hc = hc * 31 + scale.hashCode();
        this.hashCode = hc;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public int hashCode() {
        return hashCode;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public boolean equals(Object obj) {
        if (obj != null && obj instanceof PageKey) {
          PageKey other = (PageKey) obj;
          return page.equals(other.page) && rotation.equals(other.rotation) && scale.equals(other.scale);
        }
        return false;
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public String toString() {
        StringBuilder key = new StringBuilder();
        key.append(page);
        key.append(',');
        key.append(rotation);
        key.append(',');
        key.append(scale);
        return key.toString();
      }
    }

    public class ImageFile {
      protected final File   file;

      protected final String name;

      protected final String type;

      protected ImageFile(File file, String name, String type) {
        super();
        this.file = file;
        this.name = name;
        this.type = type;
      }

      /**
       * @return the file length
       */
      public long getLength() {
        return file.length();
      }

      /**
       * @return the type
       */
      public String getType() {
        return type;
      }

      /**
       * @return the name
       */
      public String getName() {
        return name;
      }

      public InputStream getStream() {
        try {
          return new FileInputStream(file);
        } catch (FileNotFoundException e) {
          throw new DocumentNotFoundException("Page image file not found: " + file.getAbsolutePath(), e);
        }
      }

      protected boolean delete() {
        return file.delete();
      }
    }

    protected final FileKey                               key;

    protected final File                                  file;

    protected final String                                name;

    protected final long                                  lastModified;

    protected final int                                   numberOfPages;

    protected final Map<String, String>                   metadata = new HashMap<String, String>();

    protected final ConcurrentHashMap<PageKey, ImageFile> pages    = new ConcurrentHashMap<PageKey, ImageFile>();

    protected long                                        lastAcccessed;

    protected PDFFile(FileKey key, File file, String name, long lastModified, Document document) {
      this.key = key;
      this.name = name;
      this.file = file;
      this.lastModified = lastModified;
      this.numberOfPages = document.getNumberOfPages();
      putDocumentInfo(document.getInfo());
      touch();
    }

    private long touch() {
      lastAcccessed = System.currentTimeMillis();
      return lastAcccessed;
    }

    private void putDocumentInfo(PInfo documentInfo) {
      if (documentInfo != null) {
        if (documentInfo.getTitle() != null && documentInfo.getTitle().length() > 0) {
          metadata.put("title", documentInfo.getTitle());
        }
        if (documentInfo.getAuthor() != null && documentInfo.getAuthor().length() > 0) {
          metadata.put("author", documentInfo.getAuthor());
        }
        if (documentInfo.getSubject() != null && documentInfo.getSubject().length() > 0) {
          metadata.put("subject", documentInfo.getSubject());
        }
        if (documentInfo.getKeywords() != null && documentInfo.getKeywords().length() > 0) {
          metadata.put("keyWords", documentInfo.getKeywords());
        }
        if (documentInfo.getCreator() != null && documentInfo.getCreator().length() > 0) {
          metadata.put("creator", documentInfo.getCreator());
        }
        if (documentInfo.getProducer() != null && documentInfo.getProducer().length() > 0) {
          metadata.put("producer", documentInfo.getProducer());
        }
        if (documentInfo.getCreationDate() != null) {
          metadata.put("creationDate", documentInfo.getCreationDate().toString());
        }
        if (documentInfo.getModDate() != null) {
          metadata.put("modDate", documentInfo.getModDate().toString());
        }
      }
    }

    public boolean remove() {
      boolean res = true;
      for (ImageFile pageFile : pages.values()) {
        res &= pageFile.delete();
      }
      return res ? file.delete() : false;
    }

    public boolean exists() {
      return file.exists();
    }

    public int getNumberOfPages() {
      return numberOfPages;
    }

    public Map<String, String> getMetadata() {
      return metadata;
    }

    /**
     * @return the lastModified
     */
    public long getLastModified() {
      return lastModified;
    }

    /**
     * @return the lastAcccessed
     */
    public long getLastAcccessed() {
      return lastAcccessed;
    }

    /**
     * @return the name
     */
    public String getName() {
      return name;
    }

    public ImageFile getPageImage(int page, float rotation, float scale) throws IOException {
      touch();
      PageKey key = new PageKey(page, rotation, scale);
      ImageFile pageFile = pages.get(key);
      if (pageFile == null) {
        File image = buildFileImage(file, page, rotation, scale);
        ImageFile imageFile = new ImageFile(image, name + "-" + key + PAGE_IMAGE_EXT, PAGE_IMAGE_TYPE);
        ImageFile alreadyCreated = pages.putIfAbsent(key, imageFile);
        if (alreadyCreated != null) {
          // already created by another thread
          pageFile = alreadyCreated;
          imageFile.delete(); // and delete this work
        } else {
          pageFile = imageFile;
        }
      }
      return pageFile;
    }

    public InputStream getStream() {
      touch();
      try {
        return new FileInputStream(file);
      } catch (FileNotFoundException e) {
        throw new DocumentNotFoundException("Document file not found: " + file.getAbsolutePath(), e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMimeType() {
      return PDF_TYPE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTypeMode() {
      return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLength() {
      return file.length();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
      if (obj != null && obj instanceof PDFFile) {
        PDFFile other = (PDFFile) obj;
        return file.equals(other.file);
      }
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return file.getAbsolutePath();
    }
  }

  protected class Evicter implements Runnable {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      for (Iterator<PDFFile> fiter = spool.values().iterator(); fiter.hasNext();) {
        PDFFile file = fiter.next();
        if (System.currentTimeMillis() - file.lastAcccessed > FILE_LIVE_TIME) {
          if (file.remove()) {
            fiter.remove();
          }
        }
      }
    }
  }

  protected class FilesCleaner extends BaseCloudDriveListener {

    final Map<String, PDFFile> files = new ConcurrentHashMap<String, PDFFile>();

    void addFile(PDFFile file) {
      PDFFile prev = files.put(file.getName(), file);
      if (prev != null) {
        prev.remove();
      }
    }

    void removeFile(PDFFile file) {
      files.remove(file.getName());
    }

    void cleanAll() {
      for (Iterator<PDFFile> fiter = files.values().iterator(); fiter.hasNext();) {
        PDFFile file = fiter.next();
        if (file.remove()) {
          spool.remove(file.key);
          fiter.remove();
        } else {
          LOG.warn("Cannot remove preview file: " + file.getName());
        }
      }
    }

    void cleanFile(String name) {
      PDFFile file = files.get(name);
      if (file != null) {
        if (file.remove()) {
          spool.remove(file.key);
          files.remove(name);
        } else {
          LOG.warn("Cannot remove preview file: " + file.getName());
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDisconnect(CloudDriveEvent event) {
      cleanAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRemove(CloudDriveEvent event) {
      cleanAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSynchronized(CloudDriveEvent event) {
      // remove changed & removed
      for (CloudFile cfile : event.getChanged()) {
        cleanFile(extractName(cfile.getPath()));
      }
      for (String rpath : event.getRemoved()) {
        cleanFile(extractName(rpath));
      }
    }

  }

  protected final ConcurrentHashMap<FileKey, PDFFile>     spool    = new ConcurrentHashMap<FileKey, PDFFile>();

  protected final JodConverterService                     jodConverter;

  protected final File                                    rootDir;

  protected final ConcurrentHashMap<String, FilesCleaner> cleaners = new ConcurrentHashMap<String, FilesCleaner>();

  /**
   * 
   */
  public PDFViewerStorage(CacheService cacheService, JodConverterService jodConverter) throws IOException {
    String storageName = "CloudDrive.cmis." + PDFViewerStorage.class.getSimpleName();

    this.jodConverter = jodConverter;

    File probe = null;
    try {
      probe = File.createTempFile(storageName + "-" + System.currentTimeMillis(), ".temp");

      rootDir = new File(probe.getParentFile(), storageName);
      if (rootDir.exists()) {
        LOG.info("Cleaning PDFViewerStorage " + rootDir.getPath());
        delete(rootDir);
      }
      rootDir.mkdir();
    } catch (IOException e) {
      LOG.error("Cannot create local PDF storage: " + e.getMessage());
      throw e;
    } finally {
      if (probe != null) {
        probe.delete();
      }
    }

    // start evicter finally
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executor.schedule(new Evicter(), 30, TimeUnit.MINUTES);
  }

  public PDFFile getFile(String repository, String workspace, CloudDrive drive, String fileId) throws DriveRemovedException,
                                                                                              RepositoryException {
    FileKey key = new FileKey(repository, workspace, drive.getLocalUser(), drive.getTitle(), fileId);
    return spool.get(key);
  }

  public PDFFile createFile(String repository, String workspace, JCRLocalCMISDrive drive, CloudFile file) throws CloudDriveException,
                                                                                                         DriveRemovedException,
                                                                                                         RepositoryException,
                                                                                                         IOException {
    long lastModified = file.getModifiedDate().getTimeInMillis();

    String userId = drive.getLocalUser();
    FileKey key = new FileKey(repository, workspace, userId, drive.getTitle(), file.getId());
    PDFFile pdfFile = spool.get(key);
    if (pdfFile != null) {
      if (pdfFile.exists()) {
        if (lastModified > pdfFile.getLastModified()) {
          // file preview outdated in the storage - reset it and create a fresh representation
          if (pdfFile.remove()) {
            // null file only if it was successfully removed,
            pdfFile = null;
          } else {
            // otherwise file in use and we stay use the old version
            LOG.warn("Cannot remove PDF view of cloud file from the storage: " + file.getTitle());
          }
        }
      } else {
        spool.remove(key);
        pdfFile = null;
      }
    }

    if (pdfFile == null) {
      StringBuilder filePath = new StringBuilder();
      filePath.append(repository);
      filePath.append(File.separatorChar);
      filePath.append(workspace);
      filePath.append(File.separatorChar);
      filePath.append(userId);

      File parent = new File(rootDir, filePath.toString());
      parent.mkdirs();

      // file name
      StringBuilder fileName = new StringBuilder();
      String cleanName = extractName(file.getPath());

      fileName.append(cleanName);
      fileName.append('-');
      fileName.append(lastModified);

      String baseFileName = fileName.toString();
      String name = baseFileName;
      long counter = 1;
      File tempFile = null;
      do {
        File f = new File(parent, name);
        if (f.exists()) {
          name = baseFileName + "-" + (counter++);
        } else {
          tempFile = f;
        }
      } while (tempFile == null);

      try {
        // spool remote content to temp file, convert to PDF if required
        ContentReader content = drive.getFileContent(file.getId());
        if (file.getType().startsWith(PDF_TYPE) || file.getType().startsWith("text/pdf")
            || file.getType().startsWith("application/x-pdf")) {
          // copy content directly
          spoolToFile(content.getStream(), tempFile);
        } else {
          // convert to PDF using Jod converter
          // spool original content of cloud file to local file (file required by Jod)
          File origFile = new File(parent, name + "-tmp");
          try {
            spoolToFile(content.getStream(), origFile);
            boolean success = jodConverter.convert(origFile, tempFile, "pdf");
            // If the converting was failure then delete the content temporary file
            if (!success) {
              tempFile.delete();
            }
          } catch (OfficeException e) {
            tempFile.delete();
            throw new IOException("Error converting office document " + file.getTitle() + " (" + cleanName
                + ")", e);
          } finally {
            origFile.delete();
          }
        }

        if (tempFile.exists()) {
          // build IcePDF document and consume it in PDFFile (ContentReader)
          FileInputStream tempStream = new FileInputStream(tempFile);
          try {
            Document pdf = buildDocumentImage(tempStream, tempFile.getName());
            try {
              pdfFile = new PDFFile(key, tempFile, cleanName, lastModified, pdf);

              // listen the drive for file removal/updates to clean the storage
              addDriveListener(drive, pdfFile);

            } finally {
              pdf.dispose();
            }
          } finally {
            tempStream.close();
          }
        } else {
          throw new DocumentNotFoundException("PDF file cannot be created due to previous errors.");
        }
      } catch (IOException e) {
        tempFile.delete();
        throw e;
      } catch (CloudDriveException e) {
        tempFile.delete();
        throw e;
      } catch (RepositoryException e) {
        tempFile.delete();
        throw e;
      }

      PDFFile alreadySpooled = spool.putIfAbsent(key, pdfFile);
      if (alreadySpooled != null) {
        // another thread already spooled this file - use it
        pdfFile = alreadySpooled;
        // clean result of this spool
        tempFile.delete();
      }
    }

    return pdfFile;
  }

  // *********** internals

  private void spoolToFile(final InputStream sourceStream, final File destFile) throws IOException {
    final ReadableByteChannel source = Channels.newChannel(sourceStream);
    final OutputStream destStream = new FileOutputStream(destFile);
    final WritableByteChannel dest = Channels.newChannel(destStream);
    try {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024);
      while (source.read(buffer) >= 0 || buffer.position() != 0) {
        // prepare the buffer to be drained
        buffer.flip();
        // write to the channel, may block
        dest.write(buffer);
        // If partial transfer, shift remainder down
        // If buffer is empty, same as doing clear()
        buffer.compact();
      }
      // EOF will leave buffer in fill state
      buffer.flip();
      // make sure the buffer is fully drained.
      while (buffer.hasRemaining()) {
        dest.write(buffer);
      }
    } finally {
      source.close();
      sourceStream.close();
      dest.close();
      destStream.close();
    }
  }

  /**
   * Read IcePDF document from given file.
   * Method adopted from {@link PDFViewerService}.
   * 
   * @param input {@link File}
   * @param name {@link String}
   * @return {@link Document}
   * @throws IOException
   */
  private Document buildDocumentImage(InputStream input, String name) throws IOException {
    Document document = new Document();

    // Turn off Log of org.icepdf.core.pobjects.Document to avoid printing error stack trace in case viewing
    // a PDF file which use new Public Key Security Handler.
    // TODO: Remove this statement after IcePDF fix this
    Logger.getLogger(Document.class.toString()).setLevel(Level.OFF);

    // Capture the page image to file
    try {
      document.setInputStream(input, name);
    } catch (PDFException ex) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Error parsing PDF document " + ex);
      }
      throw new IOException("Error parsing PDF document " + name, ex);
    } catch (PDFSecurityException ex) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Error encryption not supported " + ex);
      }
      throw new IOException("Error parsing PDF document " + name, ex);
    } catch (IOException ex) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Error handling PDF document: {} {}", name, ex.toString());
      }
      throw new IOException("Error handling PDF document " + name, ex);
    }
    return document;
  }

  /**
   * Convert given page of PDF document to PNG image file.
   * Method adapted from {@link PDFViewerRESTService}.
   * 
   * @param input
   * @param pageNumber
   * @param strRotation
   * @param strScale
   * @return
   * @throws IOException
   */
  private File buildFileImage(File input, int page, float rotation, float scale) throws IOException {
    InputStream inputStream = new FileInputStream(input);
    try {
      // find free file for this page image
      File parent = input.getParentFile();

      StringBuilder fileName = new StringBuilder();
      fileName.append(input.getName());
      fileName.append('-');
      fileName.append(page);
      fileName.append(',');
      fileName.append(rotation);
      fileName.append(',');
      fileName.append(scale);

      String baseFileName = fileName.toString();
      String name = baseFileName + PAGE_IMAGE_EXT;
      long counter = 1;
      File file = null;
      do {
        File f = new File(parent, name);
        if (f.exists()) {
          name = baseFileName + "-" + (counter++) + PAGE_IMAGE_EXT;
        } else {
          file = f;
        }
      } while (file == null);

      // convert requested page to PNG image
      Document document = buildDocumentImage(inputStream, name);

      // Turn off Log of org.icepdf.core.pobjects.Stream to not print error stack trace in case
      // viewing a PDF file including CCITT (Fax format) images
      // TODO: Remove these statement and comments after IcePDF fix ECMS-3765
      Logger.getLogger(Stream.class.toString()).setLevel(Level.OFF);

      // Paint each pages content to an image and write the image to file
      BufferedImage image = (BufferedImage) document.getPageImage(page - 1,
                                                                  GraphicsRenderingHints.SCREEN,
                                                                  Page.BOUNDARY_CROPBOX,
                                                                  rotation,
                                                                  scale);

      try {
        ImageIO.write(image, "png", file);
        image.flush();
        return file;
      } catch (IOException e) {
        file.delete();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Error captiring page image " + input.getName(), e);
        }
        throw new IOException("Error captiring page image " + input.getName(), e);
      } finally {
        // clean up resources
        document.dispose();
      }
    } finally {
      inputStream.close();
    }
  }

  private boolean delete(File dir) {
    boolean res = true;
    if (dir.isDirectory()) {
      for (File child : dir.listFiles()) {
        res &= delete(child);
      }
    }
    if (!res) {
      LOG.warn("Child files not removed fully for " + dir.getAbsolutePath());
    }
    return dir.delete();
  }

  private String extractName(String nodePath) {
    int nameIndex = nodePath.lastIndexOf("/");
    int pathLen = nodePath.length();
    String name;
    if (nameIndex >= 0 && pathLen > 1 && nameIndex < pathLen - 1) {
      name = nodePath.substring(nameIndex + 1);
    } else {
      name = nodePath;
    }
    String cleanName = JCRLocalCloudDrive.cleanName(name);
    // max file length with a space for lastModified and page/rotation/scale suffix: all < 250
    return cleanName.length() > MAX_FILENAME_LENGTH ? cleanName.substring(0, MAX_FILENAME_LENGTH) : cleanName;
  }

  private String previewFileName(String name) {
    return name + PDF_EXT;
  }

  private void addDriveListener(CloudDrive drive, PDFFile file) throws DriveRemovedException,
                                                               RepositoryException {
    FilesCleaner cleaner = cleaners.get(drive.getPath());
    if (cleaner == null) {
      cleaner = new FilesCleaner();
      drive.addListener(cleaner);
    }
    cleaner.addFile(file);
  }

}

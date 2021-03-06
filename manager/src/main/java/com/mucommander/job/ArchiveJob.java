/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2012 Maxence Bernard
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.mucommander.job;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.archiver.Archiver;
import com.mucommander.commons.file.util.FileSet;
import com.mucommander.commons.io.ByteCounter;
import com.mucommander.commons.io.StreamUtils;
import com.mucommander.job.progress.JobProgressMonitor;
import com.mucommander.text.Translator;
import com.mucommander.ui.dialog.file.FileCollisionDialog;
import com.mucommander.ui.dialog.file.ProgressDialog;
import com.mucommander.ui.main.MainFrame;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This FileJob is responsible for compressing a set of files into an archive file.
 *
 * @author Maxence Bernard
 */
public class ArchiveJob extends TransferFileJob {
	private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveJob.class);
	
    /** Destination archive file */
    private AbstractFile destFile;

    /** Base destination folder's path */
    private String baseFolderPath;

    /** Archiver instance that does the actual archiving */
    private Archiver archiver;

    /** Archive format */
    private int archiveFormat;
	
    /** Optional archive comment */
    private String archiveComment;
	
    /** Lock to avoid Archiver.close() to be called while data is being written */
    private final Object ioLock = new Object();
    
    /** True if the archiver is in the final phase of finishing */
    private boolean postProcessing = false;
    
    /** Used for overriding the endDate on FileJob in case the archiver does not support streaming*/
    private long endDate = 0;


    public ArchiveJob(ProgressDialog progressDialog, MainFrame mainFrame, FileSet files, AbstractFile destFile, int archiveFormat, String archiveComment) {
        super(progressDialog, mainFrame, files);
		
        this.destFile = destFile;
        this.archiveFormat = archiveFormat;
        this.archiveComment = archiveComment;

        this.baseFolderPath = getBaseSourceFolder().getAbsolutePath(false);
    }


    ////////////////////////////////////
    // TransferFileJob implementation //
    ////////////////////////////////////

    @Override
    protected boolean processFile(AbstractFile file, Object recurseParams) {
        if(getState()==INTERRUPTED)
            return false;

        String filePath = file.getAbsolutePath(false);
        String entryRelativePath = filePath.substring(baseFolderPath.length()+1, filePath.length());

        // Process current file
        do {		// Loop for retry
            try {
                if (file.isDirectory() && !file.isSymlink()) {
                    // Create new directory entry in archive file
                    archiver.createEntry(entryRelativePath, file);

                    // Recurse on files
                    AbstractFile subFiles[] = file.ls();
                    boolean folderComplete = true;
                    for(int i=0; i<subFiles.length && getState()!=INTERRUPTED; i++) {
                        // Notify job that we're starting to process this file (needed for recursive calls to processFile)
                        nextFile(subFiles[i]);
                        if(!processFile(subFiles[i], null))
                            folderComplete = false;
                    }
					
                    return folderComplete;
                }
                else {
                    if(archiver.supportsStream()){
                        InputStream in = setCurrentInputStream(file.getInputStream());
                        // Synchronize this block to ensure that Archiver.close() is not closed while data is still being
                        // written to the archive OutputStream, this would cause ZipOutputStream to deadlock.
                        synchronized(ioLock) {
                            // Create a new file entry in archive and copy the current file
                            StreamUtils.copyStream(in, archiver.createEntry(entryRelativePath, file));
                            in.close();
                        }
                    } else {
                        //The archiver will handle it on it's own without streams
                        archiver.createEntry(entryRelativePath, file);
                    }
                    return true;
                }
            }
            // Catch Exception rather than IOException as ZipOutputStream has been seen throwing NullPointerException
            catch(Exception e) {
                // If job was interrupted by the user at the time when the exception occurred,
                // it most likely means that the exception was caused by user cancellation.
                // In this case, the exception should not be interpreted as an error.
                if(getState()==INTERRUPTED)
                    return false;

                LOGGER.debug("Caught IOException", e);
                
                int ret = showErrorDialog(Translator.get("pack_dialog.error_title"), Translator.get("error_while_transferring", file.getAbsolutePath()));
                // Retry loops
                if(ret==RETRY_ACTION) {
                    // Reset processed bytes currentFileByteCounter
                    getCurrentFileByteCounter().reset();

                    continue;
                }
                // Cancel, skip or close dialog return false
                return false;
            }
        } while(true);
    }

    @Override
    protected boolean hasFolderChanged(AbstractFile folder) {
        // This job modifies the folder where the archive is
        return folder.equalsCanonical(destFile.getParent());     // Note: parent may be null
    }


    ////////////////////////
    // Overridden methods //
    ////////////////////////

    /**
     * Overriden method to initialize the archiver and handle the case where the destination file already exists.
     */
    @Override
    protected void jobStarted() {
        super.jobStarted();

        // Check for file collisions, i.e. if the file already exists in the destination
        int collision = FileCollisionChecker.checkForCollision(null, destFile);
        if(collision!=FileCollisionChecker.NO_COLLOSION) {
            // File already exists in destination, ask the user what to do (cancel, overwrite,...) but
            // do not offer the multiple files mode options such as 'skip' and 'apply to all'.
            int choice = waitForUserResponse(new FileCollisionDialog(getProgressDialog(), getMainFrame(), collision, null, destFile, false, false));

            // Overwrite file
            if (choice== FileCollisionDialog.OVERWRITE_ACTION) {
                // Do nothing, simply continue and file will be overwritten
            }
            // 'Cancel' or close dialog interrupts the job
            else {
                interrupt();
                return;
            }
        }

        // Loop for retry
        do {
            try {
                // Tries to get an Archiver instance.
                this.archiver = Archiver.getArchiver(destFile, archiveFormat);
                this.archiver.setComment(archiveComment);

                break;
            }
            catch(Exception e) {
                int choice = showErrorDialog(Translator.get("pack_dialog.error_title"),
                                             Translator.get("cannot_write_file", destFile.getName()),
                                             new String[] {CANCEL_TEXT, RETRY_TEXT},
                                             new int[]  {CANCEL_ACTION, RETRY_ACTION}
                                             );

                // Retry loops
                if(choice == RETRY_ACTION)
                    continue;

                // 'Cancel' or close dialog interrupts the job
                interrupt();
                return;
            }
        } while(true);
    }

    /**
     * Overriden method to close the archiver.
     */
    @Override
    public void jobStopped() {
        if(getState() != FileJob.INTERRUPTED){
            postProcessing = true;
            try { 
                JobProgressMonitor.getInstance().continueUpdating();
                archiver.postProcess(); 
                JobProgressMonitor.getInstance().stopUpdating();
                endDate = System.currentTimeMillis();
            }
            catch(IOException e) {}
        }
        // TransferFileJob.jobStopped() closes the current InputStream, this will cause copyStream() to return
        super.jobStopped();

        // Synchronize this block to ensure that Archiver.close() is not closed while data is still being
        // written to the archive OutputStream, this would cause ZipOutputStream to deadlock.
        synchronized(ioLock) {
            // Try to close the archiver which in turns closes the archive OutputStream and underlying file OutputStream
            if(archiver!=null) {
                try { archiver.close(); }
                catch(IOException e) {}
            }
        }
    }

    @Override
    public long getEndDate() {
        return endDate;
    }
    
    /**
     * Returns the size of the file currently being processed, <code>-1</code> if this information is not available.
     * 
     * @return the size of the file currently being processed, -1 if this information is not available.
     */
    @Override
    public long getCurrentFileSize() {
        if(archiver != null && !archiver.supportsStream()){
            return archiver.getProcessingFile() != null ? archiver.currentFileLength() : -1;
        }
        return super.getCurrentFileSize();
    }

    /**
     * Returns the percentage of the current file that has been processed, <code>0</code> if the current file's size
     * is not available (in this case getNbCurrentFileBytesProcessed() returns <code>-1</code>).
     *
     * @return the percentage of the current file that has been processed
     */
//    public float getFilePercentDone() {
//        long currentFileSize = getCurrentFileSize();
//        if(currentFileSize<=0)
//            return 0;
//        else
//            System.out.println("archiver.writtenBytesCurrentFile() "+archiver.writtenBytesCurrentFile());
//            return archiver.writtenBytesCurrentFile()/(float)currentFileSize;
//    }
    
    @Override
    public ByteCounter getCurrentFileByteCounter() {
        ByteCounter currentFileByteCounter = super.getCurrentFileByteCounter();
        if(archiver != null && !archiver.supportsStream()){
            currentFileByteCounter.set(archiver.writtenBytesCurrentFile());
        }
        return currentFileByteCounter;
    }

    /**
     * Returns a {@link ByteCounter} that holds the total number of bytes that have been processed by this job so far.
     *
     * @return a ByteCounter that holds the total number of bytes that have been processed by this job so far
     */
    public ByteCounter getTotalByteCounter() {
        //refresh current file byte counter
        getCurrentFileByteCounter();
        
        ByteCounter totalByteCounter = super.getTotalByteCounter();
        if(archiver != null && !archiver.supportsStream()){
            long totalWrittenBytes = archiver.totalWrittenBytes();
            totalByteCounter.set(totalWrittenBytes);
        }
        return totalByteCounter;
    }
    
    /**
     * Returns the percentage of the current file that has been processed, <code>0</code> if the current file's size
     * is not available (in this case getNbCurrentFileBytesProcessed() returns <code>-1</code>).
     *
     * @return the percentage of the current file that has been processed
     */

    @Override
    public String getStatusString() {
        return postProcessing ? archiver != null && archiver.getProcessingFile() != null ? Translator.get("pack_dialog.packing_file", "'" + archiver.getProcessingFile() + "'") : Translator.get("preparing.archive") + ": "+ Translator.get("can_take_a_while") : Translator.get("indexing") + " '"+ getCurrentFilename() + "'";
    }
    
    @Override
    public boolean supportThroughputLimit() {
        return Archiver.SUPPORTS_FILE_STREAMING[archiveFormat];
    }
}

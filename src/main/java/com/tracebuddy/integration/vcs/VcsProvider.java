package com.tracebuddy.integration.vcs;

/**
 * Interface for Version Control System providers
 * Supports GitHub, Azure DevOps, GitLab, Bitbucket, etc.
 */
public interface VcsProvider {
    
    /**
     * Read a file by class name or file name from the repository
     */
    String readFileByClassName(String className);
    
    /**
     * Read a file from the repository by path
     */
    String readFile(String path);
    
    /**
     * List files in a directory
     */
    String listFiles(String directory);
    
    /**
     * Get repository information
     */
    String getRepositoryInfo();
    
    /**
     * Test connection to the VCS
     */
    boolean testConnection();
    
    /**
     * Get provider type
     */
    String getProviderType();
}

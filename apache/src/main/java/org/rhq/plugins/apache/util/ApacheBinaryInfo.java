package org.rhq.plugins.apache.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.rhq.core.pluginapi.util.FileUtils;
import org.rhq.core.system.ProcessExecution;
import org.rhq.core.system.ProcessExecutionResults;
import org.rhq.core.system.SystemInfo;
import org.rhq.core.system.SystemInfoFactory;

/**
 * TODO
 * 
 * @author Ian Springer
 */
public class ApacheBinaryInfo
{

   private static final Log LOG =
         LogFactory.getLog(ApacheBinaryInfo.class.getName());

   private static final Map<String, ApacheBinaryInfo> CACHE = new HashMap<String, ApacheBinaryInfo>();
   private static final String APACHE_VERSION = "Apache/";
   private static final String SERVER_BUILT = "Server built:";
   private static final String MPM_DIR = "-D APACHE_MPM_DIR=\"";

   private static final String HTTPD_ROOT_DEFINE = "HTTPD_ROOT";
   private static final String SERVER_CONFIG_FILE_DEFINE = "SERVER_CONFIG_FILE";

   private String version;
   private String root;
   private String binaryPath;
   private String ctl;
   private String built;
   private String mpm;
   private long lastModified = 0;

   private ApacheBinaryInfo(@NotNull String binaryPath)
   {
      this.binaryPath = binaryPath;
   }

   @NotNull
   public static synchronized ApacheBinaryInfo getInfo(String binaryPath, SystemInfo systemInfo) throws Exception
   {
      ApacheBinaryInfo info = CACHE.get(binaryPath);
      long lastModified = new File(binaryPath).lastModified();

      if (info == null || lastModified != info.lastModified)
      {
         info = new ApacheBinaryInfo(binaryPath);
         CACHE.put(binaryPath, info);
         info.getApacheBinaryInfo(binaryPath, systemInfo);
      }
      return info;
   }

   private String findVersion(String binaryPath) throws Exception
   {
      String line = FileUtils.findString(binaryPath, APACHE_VERSION);
      if (line == null)
      {
         throw new Exception(
               "Unable to find '" + APACHE_VERSION + "' in: " +
                     binaryPath);
      }

      int spaceIndex = line.indexOf(" ");
      if (spaceIndex != -1)
      {
         line = line.substring(0, spaceIndex);
      }
      int slashIndex = line.lastIndexOf('/');
      String version = line.substring(slashIndex + 1);
      return version;
   }

   private String findDefine(String binaryPath, String name)
         throws Exception
   {
      String define = "-D " + name + "=\"";
      String line = FileUtils.findString(binaryPath, define);
      if (line == null)
      {
         throw new Exception(
               "Unable to find -D " + name + " in: " +
                     binaryPath);
      }

      String value =
            line.substring(define.length(),
                  line.length() - 1);

      if (value.length() == 0)
      {
         LOG.debug(
               "Found -D " + name + " in: " +
                     binaryPath + " but value is empty");
         value = null; //e.g. debian's apache2
      }

      return value;
   }

   private String findRoot(String binaryPath) throws Exception
   {
      String root = findDefine(binaryPath, HTTPD_ROOT_DEFINE);
      if (root == null)
      {
         String file = findDefine(binaryPath, SERVER_CONFIG_FILE_DEFINE);
         if (file != null)
         {
            File conf = new File(file);
            if (conf.isAbsolute() && conf.exists())
            {
               //e.g. debian is /etc/apache2
               root = conf.getParent();
            }
         }
      }
      return root;
   }

   private void getVersionCommandInfo(String binaryPath, SystemInfo systemInfo)
   {
      BufferedReader is = null;

      try
      {
         ProcessExecution processExecution = new ProcessExecution(binaryPath);
         processExecution.setArguments(new String[]{"-V"});
         processExecution.setWaitForCompletion(10000L);
         processExecution.setCaptureOutput(true);
         ProcessExecutionResults results = systemInfo.executeProcess(processExecution);

         if (results.getError() != null)
         {
            throw results.getError();
         }

         String line;
         is = new BufferedReader(new StringReader(results.getCapturedOutput()));

         while ((line = is.readLine()) != null)
         {
            line = line.trim();
            if (line.startsWith(SERVER_BUILT))
            {
               line = line.substring(SERVER_BUILT.length()).trim();
               this.built = line;
            }
            else if (line.startsWith(MPM_DIR))
            {
               line = line.substring(MPM_DIR.length()).trim();
               int ix = line.lastIndexOf('"');
               if (ix != -1)
               {
                  line = line.substring(0, ix);
               }
               ix = line.lastIndexOf("/");
               if (ix != -1)
               {
                  line = line.substring(ix + 1);
               }
               this.mpm = line;
            }
         }
      }
      catch (Throwable t)
      {
         String msg = "Error running binary '" + binaryPath + "': " + t.getMessage();
         LOG.error(msg, t);
      }
      finally
      {
         if (is != null)
         {
            try
            {
               is.close();
            }
            catch (IOException e)
            {
            }
         }
      }
   }

   private void getApacheBinaryInfo(String binaryPath, SystemInfo systemInfo)
         throws Exception
   {
      File binaryFile = new File(binaryPath);
      if (!binaryFile.exists())
      {
         throw new IOException(binaryFile + " does not exist.");
      }
      if (binaryFile.isDirectory())
      {
         throw new IOException(binaryFile + " is a directory.");
      }
      this.lastModified = binaryFile.lastModified();

      getVersionCommandInfo(binaryPath, systemInfo);

      File libHttpd = getHttpdSharedLibrary(binaryFile);

      this.version = findVersion((libHttpd != null) ? libHttpd.getPath() : this.binaryPath);
      this.root = findRoot(binaryPath);
   }

   private File getHttpdSharedLibrary(File binaryFile) throws Exception
   {
      File libhttpd;
      File bindir = binaryFile.getParentFile();
      if (bindir == null)
      {
         throw new Exception(this.binaryPath + " has no parent directory");
      }

      if (isUnix())
      {
         //If libhttpd.so exists in libexec, then we need
         //to search that file instead of the Apache binary.
         if ((libhttpd = bindir.getParentFile()) != null)
         {
            libhttpd = new File(libhttpd, "libexec/libhttpd.so");
         }
      }
      else
      {
         //on Windows, Apache/version is in libhttpd.dll
         //the other -D FOO=BAR props are in httpd.exe
         libhttpd = new File(bindir, "libhttpd.dll");
      }
      if (libhttpd.exists())
      {
         return libhttpd;
      }
      else
      {
         return null;
      }
   }

   public Properties toProperties()
   {
      Properties props = new Properties();
      props.setProperty("exe", this.binaryPath);
      if (this.mpm != null)
      {
         props.setProperty("mpm", this.mpm);
      }
      if (this.built != null)
      {
         props.setProperty("built", this.built);
      }
      return props;
   }

   @Override
   public boolean equals(Object o)
   {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ApacheBinaryInfo that = (ApacheBinaryInfo)o;

      if (!binaryPath.equals(that.binaryPath)) return false;

      return true;
   }

   @Override
   public int hashCode()
   {
      return binaryPath.hashCode();
   }

   @Override
   public String toString()
   {
      String info =
            "version=" + this.version +
                  ", root=" + this.root +
                  ", binary=" + this.binaryPath +
                  ", ctl=" + this.ctl;

      if (this.mpm != null)
      {
         info += ", mpm=" + this.mpm;
      }

      if (this.built != null)
      {
         info += ", build=" + this.built;
      }
      return info;
   }

   public String getVersion()
   {
      return version;
   }

   @Nullable
   public String getRoot()
   {
      return root;
   }

   public String getBinaryPath()
   {
      return binaryPath;
   }

   public String getCtl()
   {
      return ctl;
   }

   public String getBuilt()
   {
      return built;
   }

   public String getMpm()
   {
      return mpm;
   }

   public long getLastModified()
   {
      return lastModified;
   }

   public static void main(String[] args) throws Exception
   {
      String binary = args[0];
      System.out.println(getInfo(binary, SystemInfoFactory.createJavaSystemInfo()));
   }

   private static boolean isUnix()
   {
      return File.separatorChar == '/';
   }

}

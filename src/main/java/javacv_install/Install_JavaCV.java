package javacv_install;

import ij.IJ;
import ij.plugin.PlugIn;
import ij.gui.GenericDialog;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;



public class Install_JavaCV implements PlugIn {
	
	
	
	//Installation parameters
	/** Base URL to the bytedeco maven repository */
	private static final String BYTEDECO_BASE_URL =
		"https://repo1.maven.org/maven2/org/bytedeco/";
	
	private static final String JAVACV_VERSION =
			"1.5.3";
	
	private static final String FFMPEG_VERSION =
			"4.2.2-1.5.3";

	/** File suffix for the 32-bit windows */
	private static final String WIN_32 = "-windows-x86.jar";

	/** File suffix for the 64-bit windows */
	private static final String WIN_64 = "-windows-x86_64.jar";

	/** File suffix for the 32-bit linux */
	private static final String LIN_32 = "-linux-x86.jar";

	/** File suffix for the 64-bit linux */
	private static final String LIN_64 = "-linux-x86_64.jar";

	/** File suffix for the mac osx */
	private static final String MAC    = "-macosx-x86_64.jar";
	
	public static boolean restartRequired = false;
	
	public static void main(String[] args) {
		if(CheckJavaCV(true)){
			IJ.log("javacv is installed");
		}
			
		else
			IJ.log("javacv install failed");
			
	}

	@Override
	public void run(String args) {
		if(CheckJavaCV(true)) {
			IJ.log("javacv is installed");
		}
		else
			IJ.log("javacv install failed");
			
	}
	
	
	static class Dependency {
		String depFilename;
		String depDirectory;
		String depURL;
		
		public Dependency(String filename, String directory, String url) {
			this.depFilename = filename;
			this.depDirectory = directory;
			this.depURL = url;
			
		}
		
		public boolean isInstalled() {
			return (new File(depDirectory+depFilename)).exists();
		}
		
		public boolean Install() throws Exception {
			boolean success = false;
			
			File directory = new File(depDirectory);
			if(!directory.exists() && !directory.mkdirs()) {
				IJ.log("Can't create folder "+depDirectory);
				IJ.showMessage("Can't create folder\n"+depDirectory);
				return success;
			}
			if(!directory.canWrite()) {
				IJ.log("No permissions to write to folder "+depDirectory);
				IJ.showMessage("No permissions to write to folder\n"+depDirectory);
				return success;
			}
			
			IJ.log("downloading " + depURL);
			InputStream is = null;
			URL url = null;
			try {
				url = new URL(depURL);
				URLConnection conn = url.openConnection();
				is = conn.getInputStream();
			} catch(MalformedURLException e1) {
				throw new Exception(depURL + " is not a valid URL");
			} catch(IOException e1) {
				throw new Exception("Can't open connection to " + depURL);
			}
			byte[] content = readFully(is);
			File out = new File(depDirectory, new File(url.getFile()).getName());
			IJ.log(" to " + out.getAbsolutePath());
			FileOutputStream fos = null;
			try {
				fos = new FileOutputStream(out);
				fos.write(content);
				fos.close();
				success = true;
			} catch(FileNotFoundException e1) {
				throw new Exception("Could not open "
					+ out.getAbsolutePath() + " for writing. "
					+ "Maybe not enough permissions?");
			} catch(IOException e2) {
				throw new Exception("Error writing to "
					+ out.getAbsolutePath());
			}
			return success;
		}
	}
	
	private static Dependency[] dependencies;
	
	private static String GetDependenciesPath(){
		char altSeparator = '/'== File.separatorChar?'\\':'/';
		String appPath = IJ.getDirectory("imagej").replace(altSeparator, File.separatorChar);
		String jarsPath = appPath+"jars"+ File.separatorChar;
		boolean fiji = false;
		ClassLoader cl = ClassLoader.getSystemClassLoader();
		URL[] urls = ((java.net.URLClassLoader) cl).getURLs();
		for (URL url: urls) 
			if (url.getFile().replace(altSeparator, File.separatorChar).contains(jarsPath)) {
				fiji = true;
				break;
			}
		
		if (!fiji) {
		cl = IJ.getClassLoader();
		urls = ((java.net.URLClassLoader) cl).getURLs();
		for (URL url: urls) 
			if (url.getFile().replace(altSeparator, File.separatorChar).contains(jarsPath)) {
				fiji = true;
				break;
			}
		}
		
		
		if (fiji) return jarsPath;
		else {
			File pluginFile; 
			String path = IJ.getDirectory("plugins");
			try {
				pluginFile = new File(Class.forName("Install_JavaCV").getProtectionDomain().getCodeSource().getLocation().toURI());
				if (pluginFile.isFile()) path = pluginFile.getParent().replace(altSeparator, File.separatorChar)+File.separator;
				else if (pluginFile.isDirectory()) {
					path = pluginFile.getPath().replace(altSeparator, File.separatorChar);
					if (!path.endsWith(File.separator)) path+=File.separator;
				}
				
				
			} catch (URISyntaxException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
			return path;
		}
	}
	
	/**
	 * Returns true if video import plugin can run.
	 */
	public static boolean CheckJavaCV(boolean showOptDlg){
	
		boolean forceReinstall = false;
		
		
		if (showOptDlg){
				GenericDialog gd = new GenericDialog("JavaCV installation options");
				String[] Options = new String[]{"Check files", "Force reinstall"};
				gd.addRadioButtonGroup("Select an option", Options, 2, 1, "Check files");
				gd.showDialog();
				if (gd.wasCanceled()) return false;
				if (gd.getNextRadioButton().equals(Options[1])) forceReinstall = true;
			}
		
		dependencies = new Dependency[8];
		String depsPath = GetDependenciesPath(), natLibsPath = depsPath;
		boolean fiji = depsPath.endsWith("jars"+File.separator);
		String platformSuffix = null;
		
		if(IJ.isLinux())
			platformSuffix = IJ.is64Bit() ? LIN_64 : LIN_32;
		else if(IJ.isWindows())
			platformSuffix = IJ.is64Bit() ? WIN_64 : WIN_32;
		else if(IJ.isMacOSX())
			platformSuffix = MAC;

		if(platformSuffix == null) {
			IJ.showMessage("Unsupported operating system");
			return false;
		}
		if (fiji)
			natLibsPath += (IJ.isLinux() ? (IJ.is64Bit() ? "linux64" : "linux32") 
										 : (IJ.isWindows() ? (IJ.is64Bit() ? "win64" : "win32") : "macosx"))
										+File.separator;
		
		
		String filename, url;
		
		//javacpp
		filename = "javacpp-"+JAVACV_VERSION;
		url = BYTEDECO_BASE_URL+"javacpp/"+JAVACV_VERSION+"/"+filename+".jar";
		dependencies[0] = new Dependency(filename+".jar", depsPath, url);
		
		filename += platformSuffix;
		url = BYTEDECO_BASE_URL+"javacpp/"+JAVACV_VERSION+"/"+filename;
		dependencies[1] = new Dependency(filename, natLibsPath, url);
		
		//javacpp-platform
		filename = "javacpp-platform-"+JAVACV_VERSION+".jar";
		url = BYTEDECO_BASE_URL+"javacpp-platform/"+JAVACV_VERSION+"/"+filename;
		dependencies[2] = new Dependency(filename, depsPath, url);
		
		//javacv
		filename = "javacv-"+JAVACV_VERSION+".jar";
		url = BYTEDECO_BASE_URL+"javacv/"+JAVACV_VERSION+"/"+filename;
		dependencies[3] = new Dependency(filename, depsPath, url);
		
		//javacv-platform
		filename = "javacv-platform-"+JAVACV_VERSION+".jar";
		url = BYTEDECO_BASE_URL+"javacv-platform/"+JAVACV_VERSION+"/"+filename;
		dependencies[4] = new Dependency(filename, depsPath, url);
		
		//ffmpeg
		filename = "ffmpeg-"+FFMPEG_VERSION;
		url = BYTEDECO_BASE_URL+"ffmpeg/"+FFMPEG_VERSION+"/"+filename+".jar";
		dependencies[5] = new Dependency(filename+".jar", depsPath, url);
		
		filename += platformSuffix;
		url = BYTEDECO_BASE_URL+"ffmpeg/"+FFMPEG_VERSION+"/"+filename;
		dependencies[6] = new Dependency(filename, natLibsPath, url);
		
		//ffmpeg-platform
		filename = "ffmpeg-platform-"+FFMPEG_VERSION+".jar";
		url = BYTEDECO_BASE_URL+"ffmpeg-platform/"+FFMPEG_VERSION+"/"+filename;
		dependencies[7] = new Dependency(filename, depsPath, url);
		
		boolean installConfirmed = false, installed = true;
		for(Dependency dep : dependencies) 
			if (forceReinstall || !dep.isInstalled()) {
				if (!forceReinstall && !installConfirmed 
					&& !(installConfirmed = IJ.showMessageWithCancel(
											"JavaCV seems not to be installed",
											"JavaCV seems not to be installed\n" +
											"Auto-install?"))) return false;
				
				try {
					if (!dep.Install()) return false;
				} catch (Exception e) {
					IJ.error(e.getMessage());
					IJ.log(e.getMessage());
					e.printStackTrace();
					installed = false;
				}
			}
			
			
			
		
			
		if (installConfirmed || forceReinstall) {
			IJ.showMessage("Please restart ImageJ now");
			restartRequired = true;
		} else restartRequired = false;
		return installed;	
	}
				
	
	

	/**
	 * Reads all bytes from the given InputStream and returns it as a
	 * byte array.
	 */
	public static byte[] readFully(InputStream is) throws Exception {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int c = 0;
		try {
			while((c = is.read()) != -1)
				buf.write(c);
			is.close();
		} catch(IOException e) {
			throw new Exception("Error reading from " + is);
		}
		return buf.toByteArray();
	}
}

package FFmpeg_FrameReader;

import java.io.File;
import java.util.Properties;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber.Exception;



import org.bytedeco.javacv.Java2DFrameConverter;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.VirtualStack;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;



public class FFmpeg_FrameReader extends VirtualStack implements AutoCloseable, PlugIn {
	private String videoFilePath;
	private String fileDirectory;
	private String fileName;
	private int nSlices;
	private Java2DFrameConverter converter;
	private FFmpegFrameGrabber grabber;
	private int frameWidth;
	private int frameHeight;
	private int currentFrame;
	private ImagePlus currentIMP;
	private	ImagePlus imp;
	private	ImageStack stack;
	//private Properties properties;
	private String[] labels;
	private long[] framesTimeStamps;
	private double frameRate;
	//static versions of dialog parameters that will be remembered
	private static boolean	   staticConvertToGray;
	private static boolean	   staticFlipVertical;
	//dialog parameters
	private boolean			   convertToGray;		//whether to convert color video to grayscale
	private boolean			   flipVertical;		//whether to flip image vertical
	private	boolean		       displayDialog = true;
	
	public void run(String arg) {
		String options = IJ.isMacro()?Macro.getOptions():null;
		if (options!=null && options.contains("select=") && !options.contains("open="))
			Macro.setOptions(options.replaceAll("select=", "open="));
		if (arg!=null && arg!="" && arg.contains("importquiet=true")) displayDialog=false;
		OpenDialog	od = new OpenDialog("Open Video File", arg);
		String fileName = od.getFileName();
		if (fileName == null) return;
		String fileDir = od.getDirectory();
		String path = fileDir + fileName;
		if (displayDialog && !showDialog(fileName))					//ask for parameters
			return;
		
		ImageStack stack = makeStack(path, convertToGray, flipVertical);
		if (stack==null || stack.getSize() == 0 || stack.getProcessor(1)==null) {
			return;
		}
		imp = new ImagePlus(WindowManager.makeUniqueName(fileName), stack);
		if (imp.getBitDepth()==16)
			imp.getProcessor().resetMinAndMax();
		
		FileInfo fi = new FileInfo();
		fi.fileName = fileName;
		fi.directory = fileDir;
		imp.setFileInfo(fi);
		if (arg.equals(""))
			imp.show();
		
	}
	
	public ImageStack makeStack (String videoFilePath, boolean convertToGray, boolean flipVertical){
		
		if ((new File(videoFilePath)).isFile()){
			fileDirectory = (new File(videoFilePath)).getParent();
			fileName = (new File(videoFilePath)).getName();
			converter = new Java2DFrameConverter();
			grabber = new FFmpegFrameGrabber(videoFilePath);
			Frame frame = null;
			if (grabber!=null) {
				try {
					grabber.start();
					long tst = grabber.getTimestamp();
					frame = grabber.grab();
					
					if (frame!=null){
						frame.timestamp = tst;
						currentIMP = new ImagePlus("",converter.convert(frame));
					}
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					return null;
				}
				nSlices = grabber.getLengthInFrames();
				frameRate = grabber.getFrameRate();
				frameWidth = grabber.getImageWidth();
				frameHeight = grabber.getImageHeight();
				labels = new String[nSlices];
				framesTimeStamps = new long[nSlices];
				currentFrame = 0;
				this.videoFilePath = videoFilePath;
				labels[currentFrame] = String.format("%8.6fs", frame.timestamp/1000000.0);
				framesTimeStamps[currentFrame] = frame.timestamp;
				stack = this;
				return stack;
	
			}
		}
		return null;
	}

	/** Parameters dialog, returns false on cancel */
	private boolean showDialog (String fileName) {
		
		if (!IJ.isMacro()) {
			convertToGray = staticConvertToGray;
			flipVertical = staticFlipVertical;
			
		}
		GenericDialog gd = new GenericDialog("AVI Reader");
		
		gd.addCheckbox("Convert to Grayscale", convertToGray);
		gd.addCheckbox("Flip Vertical", flipVertical);
		gd.setSmartRecording(true);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		convertToGray = gd.getNextBoolean();
		flipVertical = gd.getNextBoolean();
		if (!IJ.isMacro()) {
			staticConvertToGray = convertToGray;
			staticFlipVertical = flipVertical;
		}
		IJ.register(this.getClass());
		return true;
	}
	
	public void displayDialog(boolean displayDialog) {
		this.displayDialog = displayDialog;
	}
	
	/** Returns the ImagePlus opened by run(). */
	public ImagePlus getImagePlus() {
		return imp;
	}
	
	/** Returns the number of slices in this stack. */
	public int getSize() {
		return nSlices;
	}

	/** Returns the number of slices in this stack. */
	public String getVideoFilePith() {
		return videoFilePath;
	}

	/** Returns the label of the Nth image. */
	public String getSliceLabel(int n) {
		return labels[n-1];
	}
	
	/** Returns the image width of the virtual stack */
	public int getWidth() {
		return frameWidth;
	}

	/** Returns the image height of the virtual stack */
	public int getHeight() {
		return frameHeight;
	}



	/** Returns the path to the directory containing the images. */
	public String getDirectory() {
		return fileDirectory;
	}

	/** Returns the file name of the specified slice, were 1<=n<=nslices. */
	public String getFileName(int n) {
		return fileName;
	}

	/** Deletes the last slice in the stack. */
	public void deleteLastSlice() {

	}

	/** Adds an image to the end of the stack. */
	public void addSlice(String name) {

	}

	/** Deletes the specified slice, were 1<=n<=nslices. */
	public void deleteSlice(int n) {

	}
	

	/** Returns an ImageProcessor for the specified slice,
	were 1<=n<=nslices. Returns null if the stack is empty.
	 */
	public ImageProcessor getProcessor(int n) {
		if (grabber==null || n>nSlices) {
			return null;
		}
		ImageProcessor ip = null;
		if (n==currentFrame+1) 
			if (currentIMP!=null) {
				ip = currentIMP.getProcessor();
				if (convertToGray)
					ip = ip.convertToByte(false);
				if (flipVertical)
					ip.flipVertical();
				return ip;
			} else {
				
				return null;
			}
		Frame frame=null;
		if (n < currentFrame + 1) {
			try {
				grabber.restart();

				frame = grabber.grab();
				if (frame==null) {
					
					return null;
				}
			} catch (Exception e) {
				
				e.printStackTrace();
			}


			currentFrame = 0;

		}

		while(n > currentFrame + 1){
			try {
				long tst = grabber.getTimestamp();
				frame = grabber.grab();
				frame.timestamp = tst;
				
				labels[++currentFrame] = String.format("%8.6fs", frame.timestamp/1000000.0);
				framesTimeStamps[currentFrame] = frame.timestamp;
			} catch (Exception e) {
				
				e.printStackTrace();
			}
			if (frame==null) return null;
			
		}
		currentIMP = new ImagePlus("",converter.convert(frame));
		ip = currentIMP.getProcessor();
		if (convertToGray)
			ip = ip.convertToByte(false);
		if (flipVertical)
			ip.flipVertical();
		return ip;
	}


	@Override
	public void close() throws java.lang.Exception {
		if (grabber!=null){
			
			grabber.close();
		}
		
	}
	
	public double getFrameRate() {
		return frameRate;
	}
	
	public long getFrameTimeStamp(int frameNum) {
		return framesTimeStamps[frameNum];
	}

	
	

}

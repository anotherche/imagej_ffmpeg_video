/*
 * Copyright (C) 2018-2021 Stanislav Chizhik
 * FFmpeg_FrameReader - ImageJ/Fiji plugin which allows
 * import of compressed video files into a virtual stack or hyperstack. 
 * Import is done with FFmpeg library and uses org.bytedeco.javacv.FFmpegFrameGrabber class,
 * a part of javacv package (java interface to OpenCV, FFmpeg and other) by Samuel Audet.
 */

package ffmpeg_video;

import java.io.File;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Locale;
import java.awt.*;
import java.awt.event.*;

import javax.swing.JOptionPane;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import ij.IJ;
import ij.plugin.HyperStackConverter;
import ij.plugin.PlugIn;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.Menus;
import ij.Prefs;
import ij.VirtualStack;
import ij.WindowManager;
import ij.gui.NonBlockingGenericDialog;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import net.imagej.updater.CommandLine;
import ij.plugin.frame.Recorder;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.scijava.util.AppUtils;
import org.bytedeco.javacv.Frame;
//uncomment this if javacv version < 1.5 
//import static org.bytedeco.javacpp.avutil.AV_NOPTS_VALUE;
//uncomment this if javacv version >= 1.5
import static org.bytedeco.ffmpeg.global.avutil.*;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVRational;

public class FFmpeg_FrameReader extends VirtualStack implements AutoCloseable, PlugIn {
	
	private static final String[] logLevels = new String[] {"no output", "crash", "fatal errors", "non-fatal errors",  
															"warnings", "info", "detailed", "debug"};
	private static final int[] logLevCodes = new int[] {AV_LOG_QUIET, AV_LOG_PANIC, AV_LOG_FATAL, AV_LOG_ERROR,
														AV_LOG_WARNING, AV_LOG_INFO, AV_LOG_VERBOSE, AV_LOG_DEBUG};
	
	private static final String pluginVersion = "0.4.3";
	

	
	
	private String videoFilePath;
	private String fileDirectory;
	private String fileName;
	private int nTotalFrames;
	private int nb_frames_estimated;
	private int nb_frames_in_video;
	private double video_stream_duration;
	private Java2DFrameConverter converter;
	private FFmpegFrameGrabber grabber;
	private Frame frame;
	private ImageProcessor ip;
	private int frameWidth;
	private int frameHeight;
	private int currentFrame;
	private	ImagePlus imp;
	private ImagePlus previewImp;
	private	ImageStack stack;
	private String[] labels;
	private long[] framesTimeStamps;
	private double frameRate;
	private	boolean importInitiated = false;
	private long 	trueStartTime = 0L;
	
	
	//static versions of dialog parameters that will be remembered
	private static boolean	   staticConvertToGray;
	private static boolean	   staticFlipVertical;
	
	//dialog parameters
	private boolean			   	convertToGray;		//whether to convert color video to grayscale
	private boolean			   	flipVertical;		//whether to flip image vertical
	private int 				firstFrame;
	private int 				lastFrame;
	private int 				decimateBy = 1;		//import every nth frame
	private boolean 			preferStream; 		//prefer number of frames specified in video stream info
	private int 				logLevel=0;			//choose log verbosity
	
	//Hypestack parameters and constants
	public static final int CZT=0, CTZ=1;
    static final String[] orders = {"xyczt(default)", "xyctz"};
    private boolean splitRGB = false;
    private boolean convertToHS = false;
    private int ordering = CZT;
    private int nHSChannels = 1;
    private int nHSSlices = 1;
    private int nHSFrames = 1;
    
	@Override
	public void run(String arg) {
		
//		if(isRestartRequiredByInstaller()){
//			IJ.log("Please restart ImageJ to proceed with installation of necessary JavaCV libraries.");
//			IJ.showMessage("FFmpeg Viseo Import/Export", "Please restart ImageJ to proceed with installation of necessary JavaCV libraries.");
//		}
		
		
		
		if (!checkJavaCV("1.5", true, "ffmpeg")) return;
		//System.setProperty("org.bytedeco.javacpp.logger", "slf4j"); 
		//System.setProperty("org.bytedeco.javacpp.logger.debug", "true"); 
		FFmpegLogCallback.set();
		//FFmpegLogCallback.setLevel(AV_LOG_WARNING );
		av_log_set_level(AV_LOG_QUIET);
		
		OpenDialog	od = new OpenDialog("Open Video File");
		String path = od.getPath();
		if (path == null) return;
		ImageStack stack = null;
		if (showDialog(path)) {
			stack = makeStack(firstFrame, lastFrame, decimateBy, convertToGray, flipVertical);
		} else {
			if (importInitiated) {
				try {
					close();
				} catch (java.lang.Exception e) {
					
					e.printStackTrace();
				}
			}
			return;
		}
		if (stack==null || stack.getSize() == 0 || stack.getProcessor(1)==null) {
			return;
		}
		imp = new ImagePlus(WindowManager.makeUniqueName(fileName), stack);
		FileInfo fi = new FileInfo();
		fi.fileName = fileName;
		fi.directory = fileDirectory;
		imp.setFileInfo(fi);
		imp.setProperty("video_fps", frameRate);
		imp.setProperty("stack_source_type", "ffmpeg_frame_grabber");
		imp.setProperty("first_frame", firstFrame);
		imp.setProperty("last_frame", lastFrame);
		imp.setProperty("decimate_by", decimateBy);
		if (convertToHS){
			imp=HyperStackConverter.toHyperStack(imp, nHSChannels, nHSSlices, nHSFrames, convertToGray?"grayscale":"color");
		}
		if (arg.equals("")) {
			imp.show();
		}
		
	}
	
	public String getPluginVersion(){
		return pluginVersion;
	}
	
private boolean checkJavaCV(String version, boolean treatAsMinVer, String components) {
		
		String javaCVInstallCommand = "Install JavaCV libraries";
    	Hashtable table = Menus.getCommands();
		String javaCVInstallClassName = (String)table.get(javaCVInstallCommand);
		if (javaCVInstallClassName==null) {
//			IJ.showMessage("JavaCV check", "JavaCV Installer not found.\n"
//					+"Please install it from from JavaCVInstaller update site:\n"
//					+"https://sites.imagej.net/JavaCVInstaller/");
			
			int result = JOptionPane.showConfirmDialog(null,
					"<html><h2>JavaCV Installer not found.</h2>"
							+ "<br>Please install it from from JavaCVInstaller update site:"
							+ "<br>https://sites.imagej.net/JavaCVInstaller/"
							+ "<br>Do you whant it to be installed now for you?"
							+ "<br><i>you need to restart ImageJ after the install</i></html>",
							"JavaCV check",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
			if (result == JOptionPane.YES_OPTION) {
				net.imagej.updater.CommandLine updCmd = new net.imagej.updater.CommandLine(AppUtils.getBaseDirectory("ij.dir", CommandLine.class, "updater"), 80);
				updCmd.addOrEditUploadSite("JavaCVInstaller", "https://sites.imagej.net/JavaCVInstaller/", null, null, false);
				net.imagej.updater.CommandLine updCmd2 = new net.imagej.updater.CommandLine(AppUtils.getBaseDirectory("ij.dir", CommandLine.class, "updater"), 80);
				updCmd2.update(Arrays.asList("plugins/JavaCV_Installer/JavaCV_Installer.jar"));
				IJ.run("Refresh Menus");
				table = Menus.getCommands();
				javaCVInstallClassName = (String)table.get(javaCVInstallCommand);
				if (javaCVInstallClassName==null) {
					IJ.showMessage("JavaCV check", "Failed to install JavaCV Installer plugin.\nPlease install it manually.");
				}
			}
			return false;
		}
		
		String installerCommand = "version="
				+ version
				+ " select_installation_option=[Install missing] "
				+ (treatAsMinVer?"treat_selected_version_as_minimal_required ":"")
				+ components;

		boolean saveRecorder = Recorder.record;		//save state of the macro Recorder
		Recorder.record = false;					//disable the macro Recorder to avoid the JavaCV installer plugin being recorded instead of this plugin
		String saveMacroOptions = Macro.getOptions();
		IJ.run("Install JavaCV libraries", installerCommand);
		if (saveMacroOptions != null) Macro.setOptions(saveMacroOptions);
		Recorder.record = saveRecorder;				//restore the state of the macro Recorder
				
		String result = Prefs.get("javacv.install_result", "");
		String launcherResult = Prefs.get("javacv.install_result_launcher", "");
		if (!(result.equalsIgnoreCase("success") && launcherResult.equalsIgnoreCase("success"))) {
			if(result.indexOf("restart")>-1 || launcherResult.indexOf("restart")>-1) {
				IJ.log("Please restart ImageJ to proceed with installation of necessary JavaCV libraries.");
				return false;
			} else {
				IJ.log("JavaCV installation failed. Trying to use JavaCV as is...");
				return true;
			}
		}
		return true;
	}
	
	
	
	/** Initializes FFmpegFrameGrabber that reads video frames 
	 * from a video file specified by <code>path</code> into stack */
	private boolean initImport(String path) {
		
		importInitiated = false;
		fileDirectory = "";
		fileName = "";
		frameRate = 0.0;
		frameWidth = 0;
		frameHeight = 0;
		nTotalFrames = 0;
		videoFilePath = "";
		trueStartTime = 0L;
		if ((new File(path)).isFile()){
			
			grabber = new FFmpegFrameGrabber(path);
			if (grabber!=null) {
				try {
					grabber.start();
					if(!grabber.hasVideo() || grabber.getLengthInFrames()<2){
						IJ.log("Not a video or number of frames <2 ("+path+")");
						IJ.log("Number of frames = "+grabber.getLengthInFrames());
						close();
						return false;
					}
						
				} catch (Exception e) {
					e.printStackTrace();
					return false;
				}

				converter = new Java2DFrameConverter();
				fileDirectory = (new File(path)).getParent();
				fileName = (new File(path)).getName();
				frameRate = grabber.getFrameRate();
				frameWidth = grabber.getImageWidth();
				frameHeight = grabber.getImageHeight();
				nb_frames_estimated = grabber.getLengthInFrames();
				AVFormatContext avctx = grabber.getFormatContext();
				int nbstr = avctx.nb_streams();
				for (int istr=0;istr<nbstr;istr++)
				{
					AVStream avstr = avctx.streams(istr);
					if (AVMEDIA_TYPE_VIDEO == avstr.codecpar().codec_type())
					{
						nb_frames_in_video = (int) avstr.nb_frames();
						
						if (nb_frames_in_video!=0 
							&& (nb_frames_in_video*1.0)/nb_frames_estimated<1.1
							&& (nb_frames_in_video*1.0)/nb_frames_estimated>0.9)
							nTotalFrames = nb_frames_in_video;
						else {
							nTotalFrames = nb_frames_estimated;
							nb_frames_in_video = 0;
						}
						
						AVRational video_stream_tb = avstr.time_base();
						video_stream_duration = Double.NaN;
						if(video_stream_tb.den()!=0)
							video_stream_duration = avstr.duration()*video_stream_tb.num()/(double)video_stream_tb.den();
						if (video_stream_duration<=0) video_stream_duration=Double.NaN;
						 
						break;
					}
				}
				
				videoFilePath = path;
				importInitiated = true;
				return true;
			}
		}
		return false;
	}
	
	@Override
	public void close() throws java.lang.Exception {
		if (grabber!=null){
			
			grabber.close();
			importInitiated = false;
		}
		
	}
	
	protected void finalize() throws Throwable{
		close();
		super.finalize();
	}
	

	
	/** Returns virtual stack into which frames are imported from a videofile specified by <b>videoPath</b>. 
	 * Parameters defining the import:
	 * @param first the starting video frame to import 
	 * (zero based, relative to the total number of frames in the videofile if negative) 
	 * @param last the ending video frame to import 
	 * (same agreement as for the <b>first</b>)
	 * @param decimateBy causes import of only every <b>decimateBy</b> video frame into the stack
	 * @param convertToGray converts color frames into 8bit gray if <code>true</code>
	 * @param flipVertical flips frames in vertical direction if <code>true</code> */
	public ImageStack makeStack (String videoPath, int first, int last, int decimateBy, boolean convertToGray, boolean flipVertical){
		if (initImport(videoPath)) {
			return makeStack (first, last, decimateBy, convertToGray, flipVertical);
		}
		return null;
	}
	
	/** Returns virtual stack into which frames are imported from a videofile specified by <b>videoPath</b>. 
	 * Parameters defining the import:
	 * @param first the starting video frame to import 
	 * (zero based, relative to the total number of frames in the videofile if negative) 
	 * @param last the ending video frame to import 
	 * (same agreement as for the <b>first</b>) */
	public ImageStack makeStack (String videoPath, int first, int last){
		if (initImport(videoPath)) {
			return makeStack (first, last, 1, false, false);
		}
		return null;
	}
	
	/** Returns virtual stack into which frames are imported from a videofile specified by <b>videoPath</b>.  */ 
	 
	public ImageStack makeStack (String videoPath){
		if (initImport(videoPath)) {
			return makeStack (0, -1, 1, false, false);
		}
		return null;
	}
	
	/** Returns hyperstack into which frames are imported from a videofile specified by <b>videoPath</b>. 
	 * Parameters defining the import:
	 * @param first the starting video frame to import 
	 * (zero based, relative to the total number of frames in the videofile if negative) 
	 * @param last the ending video frame to import 
	 * (same agreement as for the <b>first</b>)
	 * @param nSlices number of slices in Z dimension 
	 * @param nFrames number of frames in T dimension
	 * @param order order of hyperstack position coordinates in the video sequence ("xyczt(default)" and "xyctz" are currently implemented) 
	 * @param convertToGray converts color frames into 8bit gray if <code>true</code>
	 * @param flipVertical flips frames in vertical direction if <code>true</code> 
	 * @param split_RGB slits RGB frames into three channels if <code>true</code> */
	public ImageStack makeHyperStack (String videoPath, int first, int last, int nSlices, int nFrames, String order, boolean convertToGray, boolean flipVertical, boolean split_RGB){
		if (initImport(videoPath)) {
			convertToHS = true;
			splitRGB=split_RGB;
			int intOrder = CZT;
			for (int i=0; i<orders.length; i++) {
				if (order.equals(orders[i])) {
					intOrder = i;
					break;
				}
			}
			ordering = intOrder;
			nHSChannels = (splitRGB && !convertToGray)?3:1;
		    nHSSlices = nSlices;
		    nHSFrames = nFrames;
			return makeStack (first, last, 1, convertToGray, flipVertical);
		}
		return null;
	}
	
	
			
	private ImageStack makeStack (int first, int last, int decimateBy, boolean convertToGray, boolean flipVertical){
		if (!importInitiated) return null;
		if (decimateBy<1) throw new IllegalArgumentException("Incorrect decimation");
		firstFrame = first<0?nTotalFrames+first:first;
		if (firstFrame<0) firstFrame=0;
		if (firstFrame>nTotalFrames-1 ) {
			firstFrame=0;
			throw new IllegalArgumentException("First frame is out of range 0:"+(nTotalFrames-1));
		}
		lastFrame = last<0?nTotalFrames+last:last;
		if (lastFrame<firstFrame) lastFrame=firstFrame;
		if (lastFrame>nTotalFrames-1) lastFrame=nTotalFrames-1;
		this.decimateBy = decimateBy;
		this.convertToGray = convertToGray;
		this.flipVertical = flipVertical;
		labels = new String[getSize()];
		framesTimeStamps = new long[getSize()];
		currentFrame = firstFrame-1;
		stack = this;
		return stack;
	}	
	
	
	private boolean showDialog (String path) {
		
		if (!IJ.isMacro()) {
			convertToGray = staticConvertToGray;
			flipVertical = staticFlipVertical;
			
		}
		

		if (initImport(path)) {
			IJ.log("--------------");
			IJ.log("File name: "+fileName);
			IJ.log("Estimated number of frames = "+nb_frames_estimated);
			IJ.log("Frames in video stream = "+nb_frames_in_video);
			IJ.log("Total number of frames = "+nTotalFrames);
			IJ.log("Format = "+grabber.getFormat());
			IJ.log("Duration = "+(grabber.getLengthInTime()/(AV_TIME_BASE*1.0))+" s");
			IJ.log("Video duration = "+video_stream_duration+" s");
			IJ.log("Avarage frame rate = "+grabber.getFrameRate());
			IJ.log("Width = "+grabber.getImageWidth());
			IJ.log("Height = "+grabber.getImageHeight());
		
			previewImp = new ImagePlus();
			Frame previewFrame = null;
			try {
				previewFrame = grabber.grabImage();
				trueStartTime = grabber.getTimestamp();
				currentFrame=0;
			} catch (Exception e2) {
				
				e2.printStackTrace();
			}
			
			if (!IJ.isMacro()) {
				if (previewFrame!=null && previewFrame.image != null) {
					ImageProcessor previewIp = new ColorProcessor(converter.convert(previewFrame));
					previewImp.setProcessor("preview frame 0, timestamp: "+grabber.getTimestamp(),previewIp);
					previewImp.show();
					
				}
				else 
				{
					ImageProcessor previewIp = new ColorProcessor(getWidth(), getHeight());
					label(previewIp,"No frame decoded: # "+currentFrame,Color.white);
				}
			}
			
			
			
			NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Import settings");
			int numericFieldIndex = 0;
			int checkBoxIndex = 0;

			gd.addMessage("File name: "+fileName
							+"\nFormat = "+grabber.getFormat()
							+"\nWidth x Height = "+grabber.getImageWidth() +" x "+grabber.getImageHeight()
							+"\nDuration = "+(grabber.getLengthInTime()/(AV_TIME_BASE*1.0))+" s"
							+"\nVideo duration = "+video_stream_duration+" s"
							+"\nAverage frame rate = "+grabber.getFrameRate()
							+"\nEstimated number of frames = "+nb_frames_estimated
							+"\nNumber of frames specified in video stream info = "+nb_frames_in_video);
			Panel TotFramesPan = new Panel();
			gd.addPanel(TotFramesPan);
			
			final Label TotFramesLbl = new Label("Total number of frames: "+nTotalFrames);
			TotFramesPan.add(TotFramesLbl); 
			
			final Checkbox TotFramesOption = new Checkbox("Prefer number of frames specified in video stream", nTotalFrames==nb_frames_in_video);
			TotFramesOption.setEnabled(nb_frames_in_video>0);
			TotFramesPan.add(TotFramesOption);
			
			Panel previewPanel = new Panel();
			
			
			Label previewLbl = new Label("Preview frame...");
			previewPanel.add(previewLbl);
			
			final JSlider frameSlider = new JSlider(0, nTotalFrames-1, 0);
			previewPanel.add(frameSlider);
			
			final TextField previewFrameNum = new TextField("0",12);//((TextField)gd.getNumericFields().elementAt(3));
			previewPanel.add(previewFrameNum);
			
			final Button setFirstButt = new Button("Set first");
			previewPanel.add(setFirstButt);
			
			final Button setLastButt = new Button("Set last");
			previewPanel.add(setLastButt);
			
			gd.addPanel(previewPanel);
			
			
			gd.addMessage("Specify a range of frames to import from the video.\n"+
						  "Positive numbers are frame positions from the beginning (0 = the first frame).\n"+
						  "Negative numbers correspond to positions counted from the end (-1 = the last frame)");
		
			
			gd.addNumericField("First_frame", 0, 0);
			final TextField firstField = ((TextField)gd.getNumericFields().elementAt(numericFieldIndex++));
			
			gd.addNumericField("Last_frame", -1, 0);
			final TextField lastField = ((TextField)gd.getNumericFields().elementAt(numericFieldIndex++));
			
			gd.addNumericField("Number_of_frames to import", nTotalFrames, 0);
			final TextField framesToImportField = ((TextField)gd.getNumericFields().elementAt(numericFieldIndex++));
			framesToImportField.setEnabled(false);
			
			gd.addCheckbox("Convert_to_Grayscale", convertToGray);
			final Checkbox grayCheckBox =  ((Checkbox)gd.getCheckboxes().elementAt(checkBoxIndex++));
			
			gd.addCheckbox("Flip_Vertical", flipVertical);
			checkBoxIndex++;
			
			final String[] streamOperations = new String[]{"leave as is", "decimate", "transform to hyperstack"};
			gd.addChoice("Frame_sequence operations", streamOperations, streamOperations[0]);
			final Choice streamOpChoice = ((Choice)gd.getChoices().elementAt(0));
			
			gd.addNumericField("Decimate_by (select every nth frame) ", 1, 0);
			final TextField decimateField = ((TextField)gd.getNumericFields().elementAt(numericFieldIndex++));
			decimateField.setEnabled(streamOpChoice.getSelectedIndex()==1);
			
	        gd.addChoice("Hyperstack_order", orders, orders[ordering]);
	        final Choice orderChoice = (Choice) gd.getChoices().elementAt(1);
	        orderChoice.setEnabled(streamOpChoice.getSelectedIndex()==2);
	         
	        gd.addNumericField("Slices_(z):", nTotalFrames, 0);
	        final TextField slicesField = ((TextField)gd.getNumericFields().elementAt(numericFieldIndex++));
	        slicesField.setEnabled(streamOpChoice.getSelectedIndex()==2);
	        
	        gd.addNumericField("Frames_(t):", 1, 0);
	        final TextField framesField = ((TextField)gd.getNumericFields().elementAt(numericFieldIndex++));
	        framesField.setEnabled(streamOpChoice.getSelectedIndex()==2);
	        
	        gd.addCheckbox("Convert_RGB_to_3 Channel Hyperstack", splitRGB);
	        final Checkbox splitCheckBox =  ((Checkbox)gd.getCheckboxes().elementAt(checkBoxIndex++));
	        splitCheckBox.setEnabled(!grayCheckBox.getState() 
	        		&& streamOpChoice.getSelectedItem().equalsIgnoreCase(streamOperations[2]));
	        
	        gd.addChoice("Log_level", logLevels, logLevels[logLevel]);
			
			previewFrameNum.addTextListener(new TextListener() {
	            public void textValueChanged(TextEvent e) {
	            	
	            	try {
	            		if (previewFrameNum.getText().trim().isEmpty()) return;
						int frameNum = Integer.parseUnsignedInt(previewFrameNum.getText());
						if (frameNum>=nTotalFrames) frameNum = nTotalFrames - 1;
						if (frameNum != frameSlider.getValue())	frameSlider.setValue(frameNum);
						try {
							grabber.setVideoTimestamp(Math.round((long)AV_TIME_BASE * frameNum / frameRate) + trueStartTime);//setFrameNumber(frameNum);
							Frame previewFrame = grabber.grabImage();
							currentFrame=frameNum;
							ImageProcessor previewIp;
							if (previewFrame!=null && previewFrame.image != null)
							{					
								previewIp = new ColorProcessor(converter.convert(previewFrame));
							} 
							else 
							{
								previewIp = new ColorProcessor(getWidth(), getHeight());
								label(previewIp,"No frame decoded: # "+frameNum,Color.white);
								IJ.log("Null frame at "+frameNum);//+" ("+getFrameNumberRounded(grabber.getTimestamp())+")");
							}
							
							if (!IJ.isMacro()) {
								if (previewImp == null) previewImp = new ImagePlus();
								if (!previewImp.isVisible()) previewImp.show();
								previewImp.setProcessor("preview frame "+frameNum+ ", timestamp: "+grabber.getTimestamp(), previewIp);
							}
							
						} catch (Exception e1) {
							e1.printStackTrace();
						}
						
					} catch (NumberFormatException e1) {
						IJ.log("Enter a non-negative integer number");
					}
	            	
	            }
	        });
			
			frameSlider.addChangeListener(new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
						int frameNum = frameSlider.getValue();
						if (!previewFrameNum.getText().equals(String.valueOf(frameNum))) 
								previewFrameNum.setText(String.valueOf(frameNum));
				}
				
			});
			
			
			
			TotFramesOption.addItemListener(new ItemListener()
			{

				@Override
				public void itemStateChanged(ItemEvent e) {
					preferStream = TotFramesOption.getState();
					if (preferStream && nb_frames_in_video > 0) 
					{
						nTotalFrames = nb_frames_in_video;
						IJ.log("Total number of frames set according to the video stream info: "+nTotalFrames);
					}
					else 
					{
						nTotalFrames = nb_frames_estimated;
						IJ.log("Total number of frames set according to estimation: "+nTotalFrames);
					}
					TotFramesLbl.setText("Total number of frames: "+nTotalFrames);
					frameSlider.setMaximum(nTotalFrames);
					int firstVal = Integer.parseInt(firstField.getText());
					int lastVal = Integer.parseInt(lastField.getText());
					int nVidFrames = 1 + (lastVal + (lastVal<0?nTotalFrames:0))-(firstVal + (firstVal<0?nTotalFrames:0));
					framesField.setText(""+1);
					slicesField.setText(""+nVidFrames);
					framesToImportField.setText(""+nVidFrames);
				}
				
				
			});
			
			firstField.addTextListener(new TextListener(){

				@Override
				public void textValueChanged(TextEvent e) {
					try {
						int firstVal = Integer.parseInt(firstField.getText());
						int firstAbs = firstVal + (firstVal<0?nTotalFrames:0);
						if (firstAbs>=nTotalFrames-1 || firstAbs<0){
							firstField.setText("0");
							return;
						}
						int lastVal = Integer.parseInt(lastField.getText());
						int lastAbs = lastVal + (lastVal<0?nTotalFrames:0);
						if (firstAbs>=lastAbs){
							lastField.setText("-1");
							lastVal = -1;
						}
						int nVidFrames = 1 + (lastVal + (lastVal<0?nTotalFrames:0))-(firstVal + (firstVal<0?nTotalFrames:0));
						framesField.setText(""+1);
						slicesField.setText(""+nVidFrames);
						framesToImportField.setText(""+nVidFrames);
					} catch (NumberFormatException e1) {
					}
					
					
				}});
			
			lastField.addTextListener(new TextListener(){

				@Override
				public void textValueChanged(TextEvent e) {
					try {
						int firstVal = Integer.parseInt(firstField.getText());
						int firstAbs = firstVal + (firstVal<0?nTotalFrames:0);
						int lastVal = Integer.parseInt(lastField.getText());
						int lastAbs = lastVal + (lastVal<0?nTotalFrames:0);
						if (lastAbs>=nTotalFrames || lastAbs<=0){
							lastField.setText("-1");
							return;
						}
						if (lastAbs<=firstAbs){
							firstField.setText("0");
							firstVal = 0;
						}
						int nVidFrames = 1 + (lastVal + (lastVal<0?nTotalFrames:0))-(firstVal + (firstVal<0?nTotalFrames:0));
						framesField.setText(""+1);
						slicesField.setText(""+nVidFrames);
						framesToImportField.setText(""+nVidFrames);
					} catch (NumberFormatException e1) {
					}
					
					
				}});
			
			streamOpChoice.addItemListener(new ItemListener(){

				@Override
				public void itemStateChanged(ItemEvent e) {
					String selected = streamOpChoice.getSelectedItem();
					boolean decimate = selected.equalsIgnoreCase(streamOperations[1]);
					boolean makeHS = selected.equalsIgnoreCase(streamOperations[2]);
					decimateField.setEnabled(decimate);
					slicesField.setEnabled(makeHS);
					framesField.setEnabled(makeHS);
					orderChoice.setEnabled(makeHS);
					splitCheckBox.setEnabled(!grayCheckBox.getState() && makeHS);
				}});
			
			grayCheckBox.addItemListener(new ItemListener(){

				@Override
				public void itemStateChanged(ItemEvent e) {
					String selected = streamOpChoice.getSelectedItem();
					splitCheckBox.setEnabled(!grayCheckBox.getState() && selected.equalsIgnoreCase(streamOperations[2]));
				}});
			
			setFirstButt.addActionListener(new ActionListener(){

				@Override
				public void actionPerformed(ActionEvent e) {
					int position = frameSlider.getValue();
					if (position == nTotalFrames - 1){
						IJ.showMessage("The last frame cannot be selectd as first!");
						return;
					}
					int lastVal = Integer.parseInt(lastField.getText());
					int lastAbs = lastVal + (lastVal<0?nTotalFrames:0);
					if (lastAbs<=position) lastAbs = nTotalFrames - 1;
					firstField.setText(""+position);
					lastField.setText(""+lastAbs);
					
					
				}});
			
			setLastButt.addActionListener(new ActionListener(){

				@Override
				public void actionPerformed(ActionEvent e) {
					int position = frameSlider.getValue();
					if (position == 0){
						IJ.showMessage("The first frame cannot be selectd as last!");
						return;
					}
					int firstVal = Integer.parseInt(firstField.getText());
					int firstAbs = firstVal + (firstVal<0?nTotalFrames:0);
					if (firstAbs>=position) firstAbs = 0;
					firstField.setText(""+firstAbs);
					lastField.setText(""+position);
					
					
				}});
			
			gd.setSmartRecording(true);
			gd.pack();
			gd.showDialog();
			previewImp.changes=false;
			previewImp.close();
			if (gd.wasCanceled()) return false;
			try {
				grabber.restart();
				currentFrame=-1;
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			
			firstFrame = (int) gd.getNextNumber();
			lastFrame = (int) gd.getNextNumber();
			int numFramesToImport = (int) gd.getNextNumber(); //workaround for unnecessary NumericField
			convertToGray = gd.getNextBoolean();
			flipVertical = gd.getNextBoolean();
			String streamOp = gd.getNextChoice();
			convertToHS = false;
			decimateBy = 1;
			int decby = (int) gd.getNextNumber();
			if (streamOp.equalsIgnoreCase(streamOperations[1]) ) decimateBy = decby;
			else if (streamOp.equalsIgnoreCase(streamOperations[2])) convertToHS = true;
			ordering = gd.getNextChoiceIndex();
	        nHSChannels = 1;
	        nHSSlices = (int) gd.getNextNumber();
	        nHSFrames = (int) gd.getNextNumber();
	            splitRGB = gd.getNextBoolean();
	            if (splitRGB && !convertToGray)
	            	nHSChannels = 3;
			
			if (!IJ.isMacro()) {
				staticConvertToGray = convertToGray;
				staticFlipVertical = flipVertical;
			}
			
			logLevel=gd.getNextChoiceIndex();
			av_log_set_level(logLevCodes[logLevel]);
			
			IJ.register(this.getClass());
			return true;
		} else {
			IJ.showMessage("Error", "The file cannot be open as video");
		}
		return false;
		
	}
	
	
	
	
	private void label(ImageProcessor ip, String msg, Color color) {
		int size = getHeight()/20;
		if (size<9) size=9;
		Font font = new Font("Helvetica", Font.PLAIN, size);
		ip.setFont(font);
		ip.setAntialiasedText(true);
		ip.setColor(color);
		ip.drawString(msg, size, size*2);
	}

	
	private int translateHStoVideoPosition(int n){
		if (!convertToHS) return n;
		int T, Z, n_video=1;
		T = (((n-1)/nHSChannels)%nHSSlices)+1;
		Z = (((n-1)/(nHSChannels*nHSSlices))%nHSFrames)+1;
		if (ordering==CZT){
			n_video = (Z-1)*nHSSlices + T;
		} else if (ordering==CTZ){
			n_video = (T-1)*nHSFrames + Z;
		}
		return n_video;
	}
	
	/** Returns an ImageProcessor for the specified slice,
	were 1<=n<=nslices. Returns null if the stack is empty.
	 */
	public ImageProcessor getProcessor(int n) {
		if (grabber==null || n>getSize() || n<1) {
			throw new IllegalArgumentException("Slice is out of range "+n);
		}
		int n_video = translateHStoVideoPosition(n);

		Frame resFrame=null;
		long tst=0;
		
		if(((n_video-1)*decimateBy+firstFrame!=currentFrame)) {
			if ((n_video-1)*decimateBy+firstFrame==currentFrame+1 && n_video>1 && frame!=null) {
				try {
					resFrame = grabber.grabImage();
					tst = grabber.getTimestamp();
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				try {
					if ((n_video-1)*decimateBy+firstFrame==0) {
						if (currentFrame>0) grabber.restart();
						resFrame = grabber.grabImage();
						tst = grabber.getTimestamp();
					} else if ((n_video-1)*decimateBy+firstFrame>0) {
						grabber.setVideoTimestamp(Math.round((long)AV_TIME_BASE * ((n_video-1)*decimateBy+firstFrame) / frameRate) + trueStartTime);
						resFrame = grabber.grabImage();
						tst = grabber.getTimestamp();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			currentFrame = (n_video-1)*decimateBy+firstFrame;
			labels[n_video-1] = String.format(Locale.US, "%8.6f s", tst/(double)AV_TIME_BASE);
			framesTimeStamps[n_video-1] = tst;
			frame=resFrame;
			if (resFrame!=null && resFrame.image != null) {
				ip = new ColorProcessor(converter.convert(frame));
				if (flipVertical)
					ip.flipVertical();
				if (convertToGray)
					ip = ip.convertToByte(false);
				else if (!convertToGray && splitRGB){
					int C = ((n-1)%nHSChannels)+1;
					ip = new ByteProcessor(getWidth(), getHeight(), ((ColorProcessor)ip).getChannel(C));
					
				}
				
				
			} else {
				ip = new ColorProcessor(getWidth(), getHeight());
				label(ip,"No frame decoded: # "+currentFrame+" at "+(tst/(double)AV_TIME_BASE),Color.white);
			}

		}
		if (ip==null) {
			throw new NullPointerException("No ImageProcessor created after last grabFrame "+(n_video+firstFrame-1));
		}
		return ip;
	}
	
	
	public double getFrameRate() {
		return frameRate;
	}
	
	public long getFrameTimeStamp(int frameNum) {
		return framesTimeStamps[frameNum];
	}

	
	public int getFrameNumberRounded(long timestamp) {
		return (int) Math.round(timestamp*getFrameRate()/(double)AV_TIME_BASE);
	}
	
	/** Returns the ImagePlus opened by run(). */
	public ImagePlus getImagePlus() {
		return imp;
	}
	
	/** Returns the number of slices in this stack. */
	public int getSize() {
		int range = (lastFrame + (lastFrame<0?nTotalFrames:0))-(firstFrame + (firstFrame<0?nTotalFrames:0));//lastFrame==-1?nTotalFrames-firstFrame-1:lastFrame-firstFrame; 
		return (range/decimateBy +1)*nHSChannels;
	}
	
	/** Returns total number of frames in the video file. */
	public int getTotalSize() {
		return nTotalFrames;
	}

	/** Returns the path to the source video file */
	public String getVideoFilePath() {
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



	/** Returns the path to the directory containing the videofile. */
	public String getDirectory() {
		return fileDirectory;
	}

	/** Returns the file name of the specified slice, were 1<=n<=nslices. */
	public String getFileName(int n) {
		return fileName;
	}

	/** Deletes the last slice in the stack.
	 * not implemented for the stack of imported video frames */
	public void deleteLastSlice() {

	}

	/** Adds an image to the end of the stack. 
	 * not implemented for the stack of imported video frames */
	public void addSlice(String name) {

	}

	/** Deletes the specified slice, were 1<=n<=nslices. 
	 * not implemented for the stack of imported video frames*/
	public void deleteSlice(int n) {

	}
	
}


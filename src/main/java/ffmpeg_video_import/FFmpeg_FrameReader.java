package ffmpeg_video_import;

//uncomment this if javacv version < 1.5 
//import static org.bytedeco.javacpp.avutil.AV_NOPTS_VALUE;
//uncomment this if javacv version >= 1.5
import static org.bytedeco.ffmpeg.global.avutil.*;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.Locale;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber.Exception;




import org.bytedeco.javacv.Java2DFrameConverter;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.WindowManager;
import ij.gui.NonBlockingGenericDialog;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;



public class FFmpeg_FrameReader extends VirtualStack implements AutoCloseable, PlugIn {
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
	private	boolean displayDialog = true;
	private	boolean importInitiated = false;
	//static versions of dialog parameters that will be remembered
	private static boolean	   staticConvertToGray;
	private static boolean	   staticFlipVertical;
	//dialog parameters
	private boolean			   	convertToGray;		//whether to convert color video to grayscale
	private boolean			   	flipVertical;		//whether to flip image vertical
	private int 				firstFrame;
	private int 				lastFrame;
	private int 				decimateBy = 1;
	private long 				startTime = 0L;
	private long 				trueStartTime = 0L;
		
	public void run(String arg) {
//		String options = IJ.isMacro()?Macro.getOptions():null;
//		if (options!=null && options.contains("select=") && !options.contains("open="))
//			Macro.setOptions(options.replaceAll("select=", "open="));
		if (arg!=null && !arg.isEmpty()) {
			if (arg.contains("importquiet=true")) displayDialog=false;
		}
		

		
		OpenDialog	od = new OpenDialog("Open Video File", arg);
		String fileName = od.getFileName();
		if (fileName == null) return;
		String fileDir = od.getDirectory();
		String path = fileDir + fileName;
		ImageStack stack = null;
		if (displayDialog) {
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
		} else stack = makeStack(path, firstFrame, lastFrame, decimateBy, convertToGray, flipVertical);
		if (stack==null || stack.getSize() == 0 || stack.getProcessor(1)==null) {
			return;
		}
		imp = new ImagePlus(WindowManager.makeUniqueName(fileName), stack);
		FileInfo fi = new FileInfo();
		fi.fileName = fileName;
		fi.directory = fileDir;
		imp.setFileInfo(fi);
		imp.setProperty("video_fps", frameRate);
		imp.setProperty("stack_source_type", "ffmpeg_frame_grabber");
		imp.setProperty("first_frame", firstFrame);
		imp.setProperty("last_frame", lastFrame);
		imp.setProperty("decimate_by", decimateBy);
		if (arg.equals("")) {
			imp.show();
		}
	}
	

	

	
	public ImageStack makeStack (String videoFilePath, int first, int last, int decimateBy, boolean convertToGray, boolean flipVertical){
		if (InitImport(videoFilePath)) {
			return makeStack (first, last, decimateBy, convertToGray, flipVertical);
		}
		return null;
	}
			
		
	
	boolean InitImport(String path) {
		if (importInitiated) {
			try {
				grabber.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
			
		}
		importInitiated = false;
		fileDirectory = "";
		fileName = "";
		frameRate = 0.0;
		frameWidth = 0;
		frameHeight = 0;
		nTotalFrames = 0;
		videoFilePath = "";
		startTime = 0L;
		trueStartTime = 0L;
		if ((new File(path)).isFile()){
			
			grabber = new FFmpegFrameGrabber(path);
			if (grabber!=null) {
				try {
					grabber.start();
				} catch (Exception e) {
					e.printStackTrace();
					return importInitiated;
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
						else nTotalFrames = nb_frames_estimated;
						
						AVRational video_stream_tb = avstr.time_base();
						video_stream_duration = Double.NaN;
						if(video_stream_tb.den()!=0)
							video_stream_duration = avstr.duration()*video_stream_tb.num()/(double)video_stream_tb.den();
						if (video_stream_duration<=0) video_stream_duration=Double.NaN;
						 
						break;
					}
				}
				
				videoFilePath = path;
				startTime = grabber.getFormatContext().start_time();
				if (startTime ==  AV_NOPTS_VALUE) startTime = 0;
				//startTime = 0;
//				LogStream.redirectSystem();
//				av_dump_format(grabber.getFormatContext(), 0, path, 0);
				importInitiated = true;
				return importInitiated;
			}
		}
		return importInitiated;
	}

	ImageStack makeStack (int first, int last, int decimateBy, boolean convertToGray, boolean flipVertical){
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
	
	/** Parameters dialog, returns false on cancel */
	private boolean showDialog (String path) {
		
		if (!IJ.isMacro()) {
			convertToGray = staticConvertToGray;
			flipVertical = staticFlipVertical;
			
		}
		

		if (InitImport(path)) {
			IJ.log("--------------");
			IJ.log("File name: "+fileName);
			IJ.log("Estimated frames = "+nb_frames_estimated);
			IJ.log("Frames in video stream = "+nb_frames_in_video);
			IJ.log("Total frames = "+nTotalFrames);
			IJ.log("Format = "+grabber.getFormat());
			IJ.log("Duration = "+(grabber.getLengthInTime()/(AV_TIME_BASE*1.0))+" s");
			IJ.log("Video duration = "+video_stream_duration+" s");
			IJ.log("Avarage frame rate = "+grabber.getFrameRate());
			IJ.log("Width = "+grabber.getImageWidth());
			IJ.log("Height = "+grabber.getImageHeight());
			//IJ.log("Start time = "+grabber.getFormatContext().start_time());
			
			//// additional metadata info 
//			IJ.log("--------------");
//			IJ.log("File Metadata:");
//			Map<String,String> md = grabber.getMetadata();
//			for (Map.Entry<String,String> entry : md.entrySet())
//				IJ.log(entry.getKey()+": "+entry.getValue());
//			
//			IJ.log("--------------");
//			IJ.log("Video Metadata:");
//				Map<String,String> vmd = grabber.getVideoMetadata();
//				for (Map.Entry<String,String> entry : vmd.entrySet())
//					IJ.log(entry.getKey()+": "+entry.getValue());
				
		
			previewImp = new ImagePlus();
			Frame frame = null;
			try {
				frame = grabber.grabImage();
				trueStartTime = grabber.getTimestamp();
				currentFrame=0;
			} catch (Exception e2) {
				
				e2.printStackTrace();
			}
			if (frame!=null && frame.image != null) {
				ImageProcessor ip = new ColorProcessor(converter.convert(frame));
				previewImp.setProcessor("preview frame 0, timestamp: "+grabber.getTimestamp(),ip);
				previewImp.show();
				
			}
			else 
			{
				ImageProcessor ip = new ColorProcessor(getWidth(), getHeight());
				label(ip,"No frame decoded: # "+currentFrame,Color.white);
			}
			
			
			
			NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Import settings");

			gd.addMessage("File name: "+fileName
							+"\nFormat = "+grabber.getFormat()
							+"\nWidth x Height = "+grabber.getImageWidth() +" x "+grabber.getImageHeight()
							+"\nDuration = "+(grabber.getLengthInTime()/(AV_TIME_BASE*1.0))+" s"
							+"\nVideo duration = "+video_stream_duration+" s"
							+"\nAverage frame rate = "+grabber.getFrameRate()
							+"\nEstimated frames = "+nb_frames_estimated
							+"\nFrames in video stream = "+nb_frames_in_video);
			Panel TotFramesPan = new Panel();
			gd.addPanel(TotFramesPan);
			final Label TotFramesLbl = new Label("Total frames to import: "+nTotalFrames);
			TotFramesPan.add(TotFramesLbl); 
			final Checkbox TotFramesOption = new Checkbox("Prefer number of frames in video stream", nTotalFrames==nb_frames_in_video);
			TotFramesOption.setEnabled(nb_frames_in_video>0);
			
			TotFramesPan.add(TotFramesOption);
			Panel previewPanel = new Panel();
			gd.addPanel(previewPanel);
			
			Label previewLbl = new Label("Preview video frame...");
			previewPanel.add(previewLbl);
			gd.addMessage("Specify a range of frames to import from video.\n"+
						  "Positive numbers are frame positions "+
						  "from the beginning (0=first frame).\n"+
						  "Negative numbers correspond to positions "+
						  "counted from the end (-1=last frame)");
			gd.addNumericField("First frame", 0, 0);
			gd.addNumericField("Last frame", -1, 0);
			gd.addCheckbox("Convert to Grayscale", convertToGray);
			gd.addCheckbox("Flip Vertical", flipVertical);
			gd.addNumericField("Decimate by (select every nth frame) ", 1, 0);
			gd.addNumericField("", 0, 0);
			
			final JSlider frameSlider = new JSlider(0, nTotalFrames-1, 0);
			final TextField previewFrameNum = ((TextField)gd.getNumericFields().elementAt(3));
			
			previewPanel.add(previewFrameNum);
			
			previewFrameNum.addTextListener(new TextListener() {
	            public void textValueChanged(TextEvent e) {
	            	
	            	try {
	            		if (previewFrameNum.getText().trim().isEmpty()) return;
						int frameNum = Integer.parseUnsignedInt(previewFrameNum.getText());
						if (frameNum>=nTotalFrames) frameNum = nTotalFrames - 1;
						if (frameNum != frameSlider.getValue())	frameSlider.setValue(frameNum);
						try {
							grabber.setTimestamp(Math.round((long)AV_TIME_BASE * frameNum / frameRate) + trueStartTime);//setFrameNumber(frameNum);
							Frame frame = grabber.grabImage();
							currentFrame=frameNum;
							ImageProcessor ip;
							if (frame!=null && frame.image != null)
							{					
								ip = new ColorProcessor(converter.convert(frame));
							} 
							else 
							{
								ip = new ColorProcessor(getWidth(), getHeight());
								label(ip,"No frame decoded: # "+frameNum,Color.white);
								IJ.log("Null frame at "+frameNum);//+" ("+getFrameNumberRounded(grabber.getTimestamp())+")");
							}
							if (previewImp == null) previewImp = new ImagePlus();
							if (!previewImp.isVisible()) previewImp.show();
							previewImp.setProcessor("preview frame "+frameNum+ ", timestamp: "+grabber.getTimestamp(), ip);
							
						} catch (Exception e1) {
							e1.printStackTrace();
						}
						
					} catch (NumberFormatException e1) {
						IJ.log("Enter a non-negative integer number");
						//e1.printStackTrace();
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
			previewPanel.add(frameSlider);
			
			TotFramesOption.addItemListener(new ItemListener()
			{

				@Override
				public void itemStateChanged(ItemEvent e) {
					boolean preferStream = TotFramesOption.getState();
					if (preferStream && nb_frames_in_video > 0) 
					{
						nTotalFrames = nb_frames_in_video;
						IJ.log("Total frames set according to stream info: "+nTotalFrames);
					}
					else 
					{
						nTotalFrames = nb_frames_estimated;
						IJ.log("Total frames set according to estimation: "+nTotalFrames);
					}
					TotFramesLbl.setText("Total frames to import: "+nTotalFrames);
					frameSlider.setMaximum(nTotalFrames);
				}
				
				
			});
			
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
			convertToGray = gd.getNextBoolean();
			flipVertical = gd.getNextBoolean();
			decimateBy = (int) gd.getNextNumber();
			if (!IJ.isMacro()) {
				staticConvertToGray = convertToGray;
				staticFlipVertical = flipVertical;
			}
			IJ.register(this.getClass());
			return true;
		} else {
			IJ.showMessage("Error", "The file cannot be open");
		}
		return false;
		
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
		int range = lastFrame==-1?nTotalFrames-firstFrame-1:lastFrame-firstFrame; 
		return range/decimateBy +1;
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
	
	
	private void label(ImageProcessor ip, String msg, Color color) {
		int size = getHeight()/20;
		if (size<9) size=9;
		Font font = new Font("Helvetica", Font.PLAIN, size);
		ip.setFont(font);
		ip.setAntialiasedText(true);
		ip.setColor(color);
		ip.drawString(msg, size, size*2);
	}

	/** Returns an ImageProcessor for the specified slice,
	were 1<=n<=nslices. Returns null if the stack is empty.
	 */
	public ImageProcessor getProcessor(int n) {
		if (grabber==null || n>getSize() || n<1) {
			throw new IllegalArgumentException("Slice is out of range "+n);
		}
		Frame resFrame=null;
		long tst=0;
		
		if(((n-1)*decimateBy+firstFrame!=currentFrame)) {
			if ((n-1)*decimateBy+firstFrame==currentFrame+1 && n>1 && frame!=null) {
				try {
					resFrame = grabber.grabImage();
					tst = grabber.getTimestamp();
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				try {
					if ((n-1)*decimateBy+firstFrame==0) {
						if (currentFrame>0) grabber.restart();
						resFrame = grabber.grabImage();
						tst = grabber.getTimestamp();
					} else if ((n-1)*decimateBy+firstFrame>0) {
						grabber.setTimestamp(Math.round((long)AV_TIME_BASE * ((n-1)*decimateBy+firstFrame) / frameRate) + trueStartTime);
						resFrame = grabber.grabImage();
						tst = grabber.getTimestamp();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			currentFrame = (n-1)*decimateBy+firstFrame;
			labels[n-1] = String.format(Locale.US, "%8.6f s", tst/(double)AV_TIME_BASE);
			framesTimeStamps[n-1] = tst;
			frame=resFrame;
			if (resFrame!=null && resFrame.image != null) {
				ip = new ColorProcessor(converter.convert(frame));
				if (convertToGray)
					ip = ip.convertToByte(false);
				if (flipVertical)
					ip.flipVertical();
			} else {
				ip = new ColorProcessor(getWidth(), getHeight());
				label(ip,"No frame decoded: # "+currentFrame+" at "+(tst/(double)AV_TIME_BASE),Color.white);
			}

		}
		if (ip==null) {
			throw new NullPointerException("No ImageProcessor created after last grabFrame "+(n+firstFrame-1));
		}
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

	
	public int getFrameNumberRounded(long timestamp) {
		return (int) Math.round(timestamp*getFrameRate()/(double)AV_TIME_BASE);
	}

}

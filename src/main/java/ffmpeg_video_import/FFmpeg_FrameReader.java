package ffmpeg_video_import;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.Locale;

import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

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
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;



public class FFmpeg_FrameReader extends VirtualStack implements AutoCloseable, PlugIn {
	private String videoFilePath;
	private String fileDirectory;
	private String fileName;
	private int nTotalFrames;
	private int firstFrame;
	private int lastFrame;
	private int decimateBy=1;
	private Java2DFrameConverter converter;
	private FFmpegFrameGrabber grabber;
	private Frame frame;
	private ImageProcessor ip;
	private int frameWidth;
	private int frameHeight;
	private int currentFrame;
	//private ImagePlus currentIMP;
	private	ImagePlus imp;
	private	ImageStack stack;
	private String[] labels;
	private long[] framesTimeStamps;
	private double frameRate;
	//private long firstTimeStamp=0;
	//static versions of dialog parameters that will be remembered
	private static boolean	   staticConvertToGray;
	private static boolean	   staticFlipVertical;
	//dialog parameters
	private boolean			   convertToGray;		//whether to convert color video to grayscale
	private boolean			   flipVertical;		//whether to flip image vertical
	private	boolean		       displayDialog = true;
	private	boolean			   importInitiated = false;
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
				nTotalFrames = grabber.getLengthInFrames();
				videoFilePath = path;
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
		if (firstFrame>nTotalFrames-1 ) throw new IllegalArgumentException("First frame is out of range 0:"+(nTotalFrames-1));
		lastFrame = last<0?nTotalFrames+last:last;
		if (lastFrame<firstFrame) lastFrame=firstFrame;
		if (lastFrame>nTotalFrames-1) lastFrame=nTotalFrames-1;
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
			IJ.log("Total frames = "+nTotalFrames);
			IJ.log("Format = "+grabber.getFormat());
			IJ.log("Duration = "+grabber.getLengthInTime());
			IJ.log("Frame rate = "+grabber.getFrameRate());
			IJ.log("Width = "+grabber.getImageWidth());
			IJ.log("Height = "+grabber.getImageHeight());
			IJ.log("Start time = "+grabber.getFormatContext().start_time());
			
			final ImagePlus imp = new ImagePlus();
			//imp.changes=false;
			Frame frame = null;
			try {
				frame = grabber.grabFrame(false, true, true, false);
				currentFrame=0;
			} catch (Exception e2) {
				
				e2.printStackTrace();
			}
			if (frame!=null) {
				ImageProcessor ip = new ColorProcessor(converter.convert(frame));
				imp.setProcessor("preview frame "+getFrameNumberRounded(grabber.getTimestamp())+ " timestamp: "+grabber.getTimestamp(),ip);
				
				imp.show();
				
			}
			
			
			
			GenericDialog gd = new GenericDialog("Import settings");
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
			
			final JSlider frameSlider = new JSlider(0, nTotalFrames, 0);
			final TextField previewFrameNum = ((TextField)gd.getNumericFields().elementAt(3));
			
			previewPanel.add(previewFrameNum);
			
			previewFrameNum.addTextListener(new TextListener() {
	            public void textValueChanged(TextEvent e) {
	            	
	            	try {
						int frameNum = Integer.parseInt(previewFrameNum.getText());
						frameSlider.setValue(frameNum);
						try {
							grabber.setFrameNumber(frameNum);
						
							Frame frame = grabber.grabFrame(false, true, true, false);
							currentFrame=frameNum;
							if (frame==null) IJ.log("Null frame at "+frameNum+" ("+getFrameNumberRounded(grabber.getTimestamp())+")");
							ImageProcessor ip = new ColorProcessor(converter.convert(frame));
							imp.setProcessor("preview frame "+getFrameNumberRounded(grabber.getTimestamp())+ " timestamp: "+grabber.getTimestamp(), ip);
							
						} catch (Exception e1) {
							e1.printStackTrace();
						}
						
					} catch (NumberFormatException e1) {
						IJ.log("Enter an integer number");
						e1.printStackTrace();
					}
	            	
	            }
	        });
			
			frameSlider.addChangeListener(new ChangeListener() {

				@Override
				public void stateChanged(ChangeEvent e) {
					int frameNum = frameSlider.getValue();
					previewFrameNum.setText(String.valueOf(frameNum));
				}
				
			});
			previewPanel.add(frameSlider);
			gd.setSmartRecording(true);
			gd.pack();
			gd.showDialog();
			imp.close();
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
			//return null;
			throw new IllegalArgumentException("Slice is out of range "+n);
		}
		Frame resFrame=null;
		long tst=0;
		
		//IJ.log("slice requested "+n);
		if(((n-1)*decimateBy+firstFrame!=currentFrame)) {
			//IJ.log("if (not current requested) n= "+n +" firstFrame = "+firstFrame+" currentFrame =  "+currentFrame );
			if ((n-1)*decimateBy+firstFrame==currentFrame+1 && n>1 && frame!=null) {
				//IJ.log("if(next req && n>1 && frame!=null) n= "+n +" firstFrame = "+firstFrame+" currentFrame =  "+currentFrame +" frame " +(frame!=null?"not null":"null"));
				try {
					resFrame = grabber.grabFrame(false,true,true,false);
					tst = grabber.getTimestamp();
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				//IJ.log("(not next or n>1 or frame==null) n= "+n +" firstFrame = "+firstFrame+" currentFrame =  "+currentFrame +" frame " +(frame!=null?"not null":"null"));
				try {
					if ((n-1)*decimateBy+firstFrame==0) {
						if (currentFrame>0) grabber.restart();
						resFrame = grabber.grabFrame(false,true,true,false);
						tst = grabber.getTimestamp();
					} else if ((n-1)*decimateBy+firstFrame>0) {
						//IJ.log("setFrameNumber");
						grabber.setFrameNumber((n-1)*decimateBy+firstFrame);
						resFrame = grabber.grabFrame(false,true,true,false);
						tst = grabber.getTimestamp();

						//probably not necessary method of frame retrieving with rewind to previous positions 
						
//						int rewind = 0;
//						//boolean wasnull=false;
//						while(resFrame==null && rewind<frameRate) {
//							//IJ.log("Rewinding "+rewind+" frame to decode...");
//							grabber.setFrameNumber(n+firstFrame-1-rewind);
//							for (int i=0; i<rewind; i++) {
//								resFrame = grabber.grabFrame(false,true,false,false);
//								//IJ.log("rewind = "+i+" of "+rewind);
//								if (resFrame==null) IJ.log("Null frame while rewinding frame # "+(n+firstFrame-rewind+i));
//							}
//							resFrame = grabber.grabFrame(false,true,true,false);
//							tst = grabber.getTimestamp();
////							if (resFrame==null){
////								IJ.log("Null frame # "+(n+firstFrame -1)+ " after rewind = "+rewind);
////								wasnull=true;
////							} else if (wasnull){
////								IJ.log("Frame # "+(n+firstFrame-1)+ " decoded after rewind = "+rewind);
////							}
//							if (rewind==n+firstFrame-1) break;
//							rewind+=rewind<10?1:10;
//							if (rewind>n+firstFrame-1) rewind=n+firstFrame-1;
//						}
						
						
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			currentFrame = (n-1)*decimateBy+firstFrame;
			labels[n-1] = String.format(Locale.US, "%8.6f s", tst/1000000.0);
			framesTimeStamps[n-1] = tst;
			frame=resFrame;
			if (resFrame!=null) {
				ip = new ColorProcessor(converter.convert(frame));
				if (convertToGray)
					ip = ip.convertToByte(false);
				if (flipVertical)
					ip.flipVertical();
			} else {
				ip = new ColorProcessor(getWidth(), getHeight());
				label(ip,"No frame decoded: # "+currentFrame+" at "+(tst/1000000.0),Color.white);
			}

		}
		if (ip==null) {
			throw new NullPointerException("No ImageProcessor created after last grabFrame "+(n+firstFrame-1));
		}
		return ip;
	}
	
	
	
	//Incorrect code inspired by some incorrect XviD test videofiles
	//made with packed bitstream option
	
//	/** Returns an ImageProcessor for the specified slice,
//	were 1<=n<=nslices. Returns null if the stack is empty.
//	 */
//	public ImageProcessor getProcessor(int n) {
//		if (grabber==null || n>getSize() || n<1) {
//			//return null;
//			throw new IllegalArgumentException("Slice is out of range "+n);
//		}
//		//ImageProcessor ip = null;
//		Frame resFrame=null;
//		long tst=0;
//		
//
//		if((n+firstFrame!=currentFrame+1)) {
//			if (n+firstFrame==currentFrame+2 && n>1 && frame!=null) {
//				//tst = n==1?firstTimeStamp:grabber.getTimestamp();
//				tst = grabber.getTimestamp();
//				try {
//					resFrame = grabber.grabFrame(false,true,true,false);
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
//			} else {
//				int rewind = (n+firstFrame)==1?0:1;
////				boolean keyfound=false;
////				int keyframenum=-1;
//				boolean wasnull=false;
//				try {
//					while(resFrame==null && rewind<10*frameRate) {// && n+firstFrame>rewind && !keyfound) {// && rewind<10) {
//						IJ.showStatus("Rewinding "+rewind+" frame to decode...");
//						grabber.setFrameNumber(n+firstFrame-rewind);
//						for (int i=0; i<rewind; i++) {
//							resFrame = grabber.grabFrame(false,true,false,false);
////							keyfound = (resFrame!=null && resFrame.keyFrame);
////							if (keyfound) {
////								keyframenum= n+firstFrame-rewind +i;
////								IJ.log("keyframe while rewinding at frame = "+keyframenum);
////							}
//							IJ.showProgress(i, rewind);
//							if (resFrame==null) IJ.log("Null frame while rewinding frame # "+(n+firstFrame-rewind+i));
//						}
//						tst = (n+firstFrame)==1?0:grabber.getTimestamp();
//						resFrame = grabber.grabFrame(false,true,true,false);
//						if (resFrame==null){
//							IJ.log("Null frame # "+(n+firstFrame -1)+ " after rewind = "+rewind);
//							wasnull=true;
//						} else if (wasnull){
//							IJ.log("Frame # "+(n+firstFrame-1)+ " decoded after rewind = "+rewind);
//						}
////						keyfound = (resFrame!=null && resFrame.keyFrame);
////						if (keyfound) {
////							keyframenum= n+firstFrame;
////							IJ.log("keyframe at frame = "+keyframenum);
////						}
//
//						//if (rewind==n+firstFrame-1 || keyfound) break;
//						if (rewind==n+firstFrame-1) break;
////						if (keyfound) {
////							IJ.showStatus("keyframe at frame = "+keyframenum);
////							break;
////						}
//						rewind+=rewind<11?1:10;
//						if (rewind>n+firstFrame-1) rewind=n+firstFrame-1;
//					}
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
//			}
//			//			if (resFrame==null) {
//			//				throw new NullPointerException("No frame decoded "+n);
//			//			}
//
//			currentFrame = n+firstFrame-1;
//			labels[n-1] = String.format(Locale.US, "%8.6f s", tst/1000000.0);
//			framesTimeStamps[n-1] = tst;
//			frame=resFrame;
//			if (resFrame!=null) {
//
//
//				ip = new ColorProcessor(converter.convert(frame));
//				if (convertToGray)
//					ip = ip.convertToByte(false);
//				if (flipVertical)
//					ip.flipVertical();
//			} else {
//				ip = new ColorProcessor(getWidth(), getHeight());
//				label(ip,"No frame decoded: # "+currentFrame+" at "+(tst/1000000.0),Color.white);
//			}
//
//		}
//		if (ip==null) {
//			throw new NullPointerException("No ImageProcessor created after last grab of frame "+(n+firstFrame));
//		}
//		return ip;
//	}
//	
	


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
		return (int) Math.round(timestamp*getFrameRate()/1000000.0);
	}

}

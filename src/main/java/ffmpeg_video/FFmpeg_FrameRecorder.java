/*
 * Copyright (C) 2018-2021 Stanislav Chizhik
 * FFmpeg_FrameRecorder - ImageJ/Fiji plugin which allows
 * saving a stack as compressed video file
 * Export is done with FFmpeg library and uses org.bytedeco.javacv.FFmpegFrameRecorder class,
 * a part of javacv package (java interface to OpenCV, FFmpeg and other) by Samuel Audet.
 */

package ffmpeg_video;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.Menus;
import ij.Prefs;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Toolbar;
import ij.io.SaveDialog;
import ij.plugin.CanvasResizer;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import net.imagej.updater.CommandLine;
import ij.plugin.frame.Recorder;

import java.awt.Button;
import java.awt.Choice;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;



//import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;
//import org.bytedeco.javacv.FrameRecorder;
//import org.bytedeco.javacv.FrameRecorder.Exception;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.scijava.util.AppUtils;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import static org.bytedeco.ffmpeg.avcodec.AVCodecContext.*;



@SuppressWarnings("deprecation")
public class FFmpeg_FrameRecorder implements AutoCloseable, PlugInFilter {
	
	static final String formatByExt = "by file extension";
	static final String[] formats = new String[] {"auto select", "avi", "mov", "mp4", "mkv", formatByExt};
	static final int defaultFormatIndex = 0;	//automatic selection by extension or by encoder;
	static final String defaultFormatForGuess = "mp4";
	static final String[] preferredExtensions = new String[] {"mp4", "mkv", "mov", "avi", "wmv"};
	static final String[] encoders = new String[] {"by format", "mpeg4", "h264", "h265", "mjpeg", "huffyuv", "custom"};
	static final int defaultEncoderIndex = 0;	//automatic selection by format
	static final int[] encCodes = new int[] {AV_CODEC_ID_NONE, AV_CODEC_ID_MPEG4, AV_CODEC_ID_H264, 
													AV_CODEC_ID_H265, AV_CODEC_ID_MJPEG, AV_CODEC_ID_HUFFYUV, -1};
	private static final String[] logLevels = new String[] {"no output", "crash", "fatal errors", "non-fatal errors",  
															"warnings", "info", "detailed", "debug"};
	private static final int[] logLevCodes = new int[] {AV_LOG_QUIET, AV_LOG_PANIC, AV_LOG_FATAL, AV_LOG_ERROR,
														AV_LOG_WARNING, AV_LOG_INFO, AV_LOG_VERBOSE, AV_LOG_DEBUG};
	
	private static final String pluginVersion = "0.4.3";
	
	
	
	private static List<AVCodec> ffmpegEncoders;
	static List<AVOutputFormat> ffmpegFormats;
	
	
	private String filePath;
	private int firstSlice, lastSlice;
	private int frameWidth, videoWidth, desiredWidth, frameWidthBorder=0;
	private int frameHeight, videoHeight, frameHeightBorder=0; 
	private int bitRate;
	private double fps=25;
	private int formatCode=0;
	private String vFormat = formats[formatCode];
	private int codecCode=0;
	private String customVEnc;
	private ArrayList<String> vKeys; 
	private ArrayList<String> vOptions;
	private JTable vcodecOptTab;
	private int logLevel=0;
	
	private	boolean progressByStackUpdate;
	private	boolean addTimeStamp;
	private	boolean initialized = false;
	
	private	ImagePlus imp;
	private	ImageStack stack;
	
	private Frame frame_ARGB;
	private Java2DFrameConverter converter;
	private FFmpegFrameRecorder recorder;
	
	//default formats for encoders
	static final Map<String, String> defaultFormats;
	static {
        Map<String, String> aMap = new HashMap<String,String>();
        aMap.put(encoders[0], defaultFormatForGuess);
        aMap.put("mpeg4", "avi");
        aMap.put("mjpeg", "avi");
        aMap.put("huffyuv", "avi");
        aMap.put("h264", "mp4");
        aMap.put("h265", "mp4");
        aMap.put("custom", "mp4");
        defaultFormats = Collections.unmodifiableMap(aMap);
        
        
 		
    }
	
	
	
	public int setup(String arg, ImagePlus imp) {
    	this.imp = imp;
        return DOES_ALL + STACK_REQUIRED + NO_CHANGES;
	}

	@Override
	public void run(ImageProcessor ip) {
		
//		if(isRestartRequiredByInstaller()){
//			IJ.log("Please restart ImageJ to proceed with installation of necessary JavaCV libraries.");
//			IJ.showMessage("FFmpeg Viseo Import/Export", "Please restart ImageJ to proceed with installation of necessary JavaCV libraries.");
//		}
		
		if (!CheckJavaCV("1.5", true, "ffmpeg")) return;
		//System.setProperty("org.bytedeco.javacpp.logger", "slf4j"); 
		//System.setProperty("org.bytedeco.javacpp.logger.debug", "true"); 
		FFmpegLogCallback.set();
		av_log_set_level(AV_LOG_QUIET);
		fillFormatsAndEncoders();
				
		stack = imp.getStack();
		ip.convertToRGB();
		frameWidth = imp.getWidth();
    	frameHeight = imp.getHeight();
		if (!showDialog())					//ask for parameters
			return;
		
		RecordVideo(filePath, imp, desiredWidth, fps, bitRate, firstSlice, lastSlice);
	}
	
	
	static void fillFormatsAndEncoders(){
		
		if (ffmpegEncoders==null || ffmpegEncoders.size()==0 || ffmpegFormats==null || ffmpegFormats.size()==0) {
			ffmpegEncoders=new ArrayList<AVCodec>();
	        ffmpegFormats=new ArrayList<AVOutputFormat>();
	        
	        AVCodec ffmpegCodec = null;
	        Pointer citer = new Pointer((Pointer)null);
	        while ((ffmpegCodec = av_codec_iterate(citer))!=null)
	        {
	            // try to get an encoder from the system
	        	AVCodec encoder = avcodec_find_encoder(ffmpegCodec.id());
	            if (encoder!=null && encoder.type()==AVMEDIA_TYPE_VIDEO)
	            {
	            	ffmpegEncoders.add(encoder);
	            }
	        }
	        ffmpegEncoders = new ArrayList<AVCodec>(new LinkedHashSet<AVCodec>(ffmpegEncoders));
	        
			AVOutputFormat ffmpegFormat = null;
			Pointer miter = new Pointer((Pointer)null);
	        while ((ffmpegFormat = av_muxer_iterate(miter))!=null)
	        {
	        	 if (ffmpegFormat.video_codec()!=0) {
	        		for(AVCodec enc : ffmpegEncoders){
	     				if (avformat_query_codec(ffmpegFormat, enc.id(), FF_COMPLIANCE_NORMAL)==1) {
	     					ffmpegFormats.add(ffmpegFormat);
	     					break;
	     				}
	     			}
	     		}
	        	 
	            
	        }
	        ffmpegFormats = new ArrayList<AVOutputFormat>(new LinkedHashSet<AVOutputFormat>(ffmpegFormats));
		}
	}
	
	public String getPluginVersion(){
		return pluginVersion;
	}
	
private boolean CheckJavaCV(String version, boolean treatAsMinVer, String components) {
		
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
	
	private String ComposeEncoderOptions() {
		DefaultTableModel model = vcodecOptTab == null?null:(DefaultTableModel) vcodecOptTab.getModel();
		String optLine="";
		vKeys = null;
		vOptions = null;
		if (model!=null) {
			int row = vcodecOptTab.getEditingRow();
			int col = vcodecOptTab.getEditingColumn();
			if (row != -1 && col != -1)
				vcodecOptTab.getCellEditor(row,col).stopCellEditing();
			int rowCount = model.getRowCount();
			if (rowCount>0) {
				vKeys = new ArrayList<String>(rowCount);
				vOptions = new ArrayList<String>(rowCount);
				String key, option;
				for (int i =0; i< rowCount; i++){
					if (model.getValueAt(i, 0)!=null && !model.getValueAt(i, 0).toString().isEmpty()) {
						if (i>0) optLine+="; ";
						key = model.getValueAt(i, 0).toString();
						optLine+=key+" ";
						vKeys.add(key);
						if (model.getValueAt(i, 1)!=null && !model.getValueAt(i, 1).toString().isEmpty())
							option = model.getValueAt(i, 1).toString();
						else option = " ";
						optLine+=option;
						vOptions.add(option);
					}
				}
			}
		}
		return optLine;
	}
	
	private void DecomposeEncoderOptions(String optLine) {
		String[] keyOptPairs = optLine.split(";");
		vKeys = null;
		vOptions = null;
		int lineLength = keyOptPairs.length;
		if (lineLength>0) {
			vKeys = new ArrayList<String>(lineLength);
			vOptions = new ArrayList<String>(lineLength);
			String key, option;
			for (int i =0; i< lineLength; i++){
				String[] pair = keyOptPairs[i].split(" ");
				if (pair.length>0) {
					key=pair[0];
					if (pair.length>1) option=pair[1];
					else option=" ";
					vKeys.add(key);
					vOptions.add(option);
				}
			}
		}
	}
	
	/** Parameters dialog, returns false on cancel */
	private boolean showDialog () {

		int initialFormatIndex = defaultFormatIndex;
		int initialEncoderIndex = defaultEncoderIndex;
		String initialCustomEnc = "";
		boolean initialShowProgressOption = false;
		boolean initialTimeStampOption = false;
		
		// saved parameters of dialog
		if (!IJ.isMacro()) {
			initialFormatIndex = (int) Prefs.get("ffmpegvideoimport.savedFormatIndex", defaultFormatIndex);
			if (initialFormatIndex>=formats.length) initialFormatIndex = defaultFormatIndex;
			initialEncoderIndex = (int) Prefs.get("ffmpegvideoimport.savedEncoderIndex", defaultEncoderIndex);
			if (initialEncoderIndex>=encoders.length) initialEncoderIndex = defaultEncoderIndex;
			initialCustomEnc = Prefs.get("ffmpegvideoimport.savedCustomEncoder", "");
			initialShowProgressOption = Prefs.get("ffmpegvideoimport.savedShowProgressOption", false);
			initialTimeStampOption = Prefs.get("ffmpegvideoimport.savedTimeStampOption", false);
		}
		
		final SetupDialog gd = new SetupDialog("Export settings", this);//NonBlockingGenericDialog("Export settings");
		gd.addMessage("Instruction: 1. Select slices to encode. 2. Set width of the output video.\n"+
						"Output heigth will be scaled proportional (both dimensions will be aligned to 8 pixels)\n"+
						"3. Specify frame rate in frames per second 4. Specify bitrate of the compressed video (in kbps).\n"+
						"The bitrate is calculated as H*W*(25 fps)*(0.1 bps)/1024. Adjust if necessary or set 0 for auto.\n"+
						"4. Select output format and video encoder. Default format is determined by the file extension\n"+
						"Default encoder is determined by the format. Select \"custom\", to use a different encoder among available.\n"+
						"5. Optionally specify additional encoder settings.\n"+
						"Remember that not all format/encoder combinations are supported.");
		gd.addNumericField("First_slice", 1, 0);
		gd.firstSliceField = (TextField)gd.getNumericFields().elementAt(0);
		gd.addNumericField("Last_slice", stack.getSize(), 0);
		gd.lastSliceField = (TextField)gd.getNumericFields().elementAt(1);
		gd.addNumericField("Video_frame_width" , frameWidth, 0);
		gd.addNumericField("Frame_rate" , 25.0, 3);
		int br = (int)((frameWidth*frameHeight*25L)/10240L);
		gd.addNumericField("Video_bitrate" , br<128?128:br, 0);
		
		gd.addChoice("Format", formats, formats[initialFormatIndex]);
		gd.formatChoice = ((Choice)gd.getChoices().elementAt(0));
		
		final Button btn_showAvailableFormats = new Button("Show available formats");
		final Panel availableFormatsPanel = new Panel();
		availableFormatsPanel.add(btn_showAvailableFormats);
		gd.addPanel(availableFormatsPanel);
		
		gd.addChoice("Encoder", encoders, encoders[initialEncoderIndex]);
		final Choice encChoice = gd.codecChoice = ((Choice)gd.getChoices().elementAt(1));
		
		//Custom encoder
		gd.addStringField("Custom_encoder", initialCustomEnc);
		final TextField customEncName = gd.customEncField = ((TextField)gd.getStringFields().elementAt(0));
		final Button btn_getEncoders = new Button("Show encoders");
		final Button btn_getCompatibleFormats = new Button("Show compatible formats");
		final Panel customEncPanel = new Panel();
		customEncPanel.setLayout(new GridLayout(2, 2, 6, 0));
		customEncPanel.add(new Label("Available encoders:"));
		customEncPanel.add(new Label("Compatible formats:"));
		customEncPanel.add(btn_getEncoders);
		customEncPanel.add(btn_getCompatibleFormats);
		gd.addPanel(customEncPanel);
		
		gd.addCheckbox("Show_progress by stack update (slows down the conversion)", initialShowProgressOption);
		gd.addCheckbox("Add_timestamp to the file name", initialTimeStampOption);
		gd.addChoice("Log_level", logLevels, logLevels[logLevel]);
		
		vcodecOptTab = null;
		final Button btn_vopt = new Button("Add encoder option");
		final Button btn_resetOptions = new Button("Reset options");
		btn_resetOptions.setEnabled(false); 

		final Panel optionsBtnPanel = new Panel();
		GridBagLayout grid = (GridBagLayout)gd.getLayout();
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 2; c.gridy = 1;
		c.gridwidth = 1;
		c.gridheight = 1;
		c.anchor = GridBagConstraints.WEST;
		grid.setConstraints(optionsBtnPanel, c);
		gd.add(optionsBtnPanel);
		optionsBtnPanel.add(btn_vopt);
		optionsBtnPanel.add(btn_resetOptions);
		
		btn_vopt.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (vcodecOptTab == null) {
					vcodecOptTab = new JTable(new DefaultTableModel(new Object[]{"Key", "Option"},1));
					JScrollPane scrollPane = new JScrollPane(vcodecOptTab,
                            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
					
					GridBagLayout grid = (GridBagLayout)gd.getLayout();
					GridBagConstraints c = new GridBagConstraints();
					c.gridx = 2; c.gridy = 2;
					c.gridwidth = 1;
					c.gridheight = GridBagConstraints.REMAINDER ;
					c.anchor = GridBagConstraints.NORTHWEST;
					Panel optionsPanel = new Panel(new GridLayout());
					optionsPanel.setPreferredSize(new Dimension(250,180));
					grid.setConstraints(optionsPanel, c);
					gd.add(optionsPanel);
					
					optionsPanel.add(scrollPane);
					
				} else {
					
					DefaultTableModel model = (DefaultTableModel) vcodecOptTab.getModel();
					model.addRow(new Object[]{"", ""});
				}
				
				btn_resetOptions.setEnabled(true); 
				gd.validate();
       		 	gd.repaint();
       		 	gd.pack();
				
			}
			
		});
		
		btn_resetOptions.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				DefaultTableModel model = vcodecOptTab == null?null:(DefaultTableModel) vcodecOptTab.getModel();
				if (model != null) {
					int row = vcodecOptTab.getEditingRow();
					int col = vcodecOptTab.getEditingColumn();
					if (row != -1 && col != -1)
						vcodecOptTab.getCellEditor(row,col).stopCellEditing();
					for (int i=0; i<model.getRowCount(); i++) {
						model.setValueAt("", i, 0);
						model.setValueAt("", i, 1);
					}
					model.setRowCount(0);
					model.fireTableDataChanged();
				}
			}
			
		});
		
		btn_getCompatibleFormats.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				int encChoiceInd = encChoice.getSelectedIndex();
				if (encChoiceInd>0) {
					AVCodec enc;
					String encName;
					if (encCodes[encChoiceInd]>0) {
						enc = avcodec_find_encoder(encCodes[encChoiceInd]);
						encName = enc.name().getString();
					}
					else {
						encName = customEncName.getText();
						if (encName.isEmpty()) return;
						enc = avcodec_find_encoder_by_name(encName);
					}
					if (enc!=null){
						IJ.log("=========================================================================");
						IJ.log("List of compatible formats for the encoder "+enc.name().getString()+" ("+enc.long_name().getString()+")");
						IJ.log("-------------------------------------------------------------------------");
						int id = enc.id();
						for(AVOutputFormat oformat : ffmpegFormats){
							int[] r = EncoderFromatCompliance(id, oformat);

							if ( r[2]==1) {
								String name = oformat.name()!=null?oformat.name().getString():" ";
								String longname = oformat.long_name()!=null?" - "+oformat.long_name().getString()+"; ":"; ";
								String extensions = oformat.extensions()!=null?oformat.extensions().getString()+"; ":" NO DEFAULT EXTENSION; ";
								AVCodec defEnc = avcodec_find_encoder(oformat.video_codec());
								String defaultCodec ="";
								if (defEnc!=null) {
									String defEncName = defEnc.name()!=null?" "+defEnc.name().getString():" ";
									String defEncLongname = defEnc.long_name()!=null?" - "+defEnc.long_name().getString()+";":";";
									defaultCodec = " default codec: "+defEncName+defEncLongname;
								}
								IJ.log(name+longname+" file extensions: "+extensions+defaultCodec);
							}
								
						}
						IJ.log("=========================================================================");
					} else {
						IJ.log("Encoder "+encName+" is unknown");
					}
				}
			}
		});
		
		btn_getEncoders.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				IJ.log("============================================================================");
				IJ.log("List of available encoders ");
				IJ.log("----------------------------------------------------------------------------");
				for(AVCodec enc : ffmpegEncoders){
					String name = enc.name()!=null?enc.name().getString():" ";
					String longname = enc.long_name()!=null?enc.long_name().getString():" ";
					IJ.log(name+" - "+longname);
				}
				IJ.log("============================================================================");
			}
			
		});
		
		btn_showAvailableFormats.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				IJ.log("=========================================================================");
				IJ.log("List of all available formats");
				IJ.log("-------------------------------------------------------------------------");
				for(AVOutputFormat oformat : ffmpegFormats){
						String name = oformat.name()!=null?oformat.name().getString():" ";
						String longname = oformat.long_name()!=null?" - "+oformat.long_name().getString()+"; ":"; ";
						String extensions = oformat.extensions()!=null?oformat.extensions().getString()+"; ":" NO DEFAULT EXTENSION; ";
						AVCodec defEnc = avcodec_find_encoder(oformat.video_codec());
						String defaultCodec ="";
						if (defEnc!=null) {
							String defEncName = defEnc.name()!=null?" "+defEnc.name().getString():" ";
							String defEncLongname = defEnc.long_name()!=null?" - "+defEnc.long_name().getString()+";":";";
							defaultCodec = " default codec: "+defEncName+defEncLongname;
						}
						IJ.log(name+longname+" file extensions: "+extensions+defaultCodec);
				}
				IJ.log("=========================================================================");
			}
		});
		
		
		gd.pack();
		gd.setSmartRecording(true);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		firstSlice = (int)Math.abs(gd.getNextNumber());
		lastSlice = (int)Math.abs(gd.getNextNumber());
		if (firstSlice<1) firstSlice=1;
		if (lastSlice>stack.getSize()) lastSlice=stack.getSize();
		if (lastSlice<=firstSlice) {
			IJ.showMessage("Error", "Incorrect slice range");
			return false;
		}
		desiredWidth=(int)Math.abs(gd.getNextNumber());
		fps=Math.abs(gd.getNextNumber());
		bitRate = (int)Math.abs(gd.getNextNumber())*1024;
		
		gd.setSmartRecording(false); //force recording of the format and encoder settings
		formatCode = gd.getNextChoiceIndex();
		codecCode= gd.getNextChoiceIndex();
		customVEnc = gd.getNextString().toLowerCase(Locale.US);
		gd.setSmartRecording(true); //return to smart recording mode
		
		if (!IJ.isMacro()) {
			String optLine = ComposeEncoderOptions();//encOptField.setText(ComposeEncoderOptions());
			if (Recorder.record) {
				if (!optLine.isEmpty()) Recorder.recordOption("additional_encoder_options", optLine);
			}
		} else {
			String macroOptions = Macro.getOptions();
			String additionalEncOpt = "";
			if (macroOptions!=null) 
				additionalEncOpt = Macro.getValue(macroOptions, "additional_encoder_options", "");
			DecomposeEncoderOptions(additionalEncOpt);
		}
		
		progressByStackUpdate = gd.getNextBoolean();
		addTimeStamp = gd.getNextBoolean();
		
		String codec = "";
		if(encCodes[codecCode]!=0) codec = "[" + (encCodes[codecCode]<0? customVEnc:avcodec_find_encoder(encCodes[codecCode]).name().getString()).toUpperCase()+"]";

		if(!IsCodecSupported(codecCode, customVEnc)) {
			IJ.showMessage("Error", "Selected encoder " + codec + " is not supported");
			return false;
		}

		String suggestedExtension = getSuggestedExtension(formatCode, codecCode, customVEnc);
		if (suggestedExtension.isEmpty()) suggestedExtension = defaultFormatForGuess;
//		if (formatCode>0) {
//			if (formats[formatCode].equals(formatByExt)) suggestedExtension = defaultFormatForGuess;
//			else suggestedExtension = formats[formatCode].toLowerCase();
//		}
//		else {
//			suggestedExtension = defaultFormats.get(encoders[codecCode]);
//			if (encCodes[codecCode]==-1) {
//				LinkedHashSet<String> compatExt = new LinkedHashSet<String>();
//				AVCodec enc = avcodec_find_encoder_by_name(customVEnc);
//				int id = enc.id();
//				for(AVOutputFormat oformat : ffmpegFormats){
//					int[] r = EncoderFromatCompliance(id, oformat);
//					if ( r[2]==1) {
//						String longname = oformat.long_name()!=null?oformat.long_name().getString():"";
//						if (longname.toLowerCase().indexOf("raw ")!=-1)continue;
//						String extensions = oformat.extensions()!=null?oformat.extensions().getString():" ";
//						compatExt.addAll(Arrays.asList(extensions.split(",")));
//					}
//						
//				}
//				if (compatExt.size()==0) suggestedExtension=defaultFormatForGuess;
//				else {
//					if (compatExt.contains(customVEnc)) {
//						suggestedExtension = customVEnc;
//					} else {
//						Iterator<String> iter =  compatExt.iterator();
//						suggestedExtension = iter.next();
//						for (String ext : preferredExtensions){
//							if (compatExt.contains(ext)) {
//								suggestedExtension = ext;
//								break;
//							}
//						}
//					}
//				}
//				
//			}
//		}
		
		String suggestedFileName = imp.getTitle()+"_stack_export";
		if (addTimeStamp) { //add_timestamp
			LocalDateTime current = LocalDateTime.now();
		    DateTimeFormatter format =
		      DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss"); 
		   String timestamp =current.format(format); 
		   suggestedFileName += "-"+timestamp;	
		
		   if (IJ.isMacro()) {
				String macroOptions = Macro.getOptions();
				if (macroOptions!=null) {
					String path = Macro.getValue(macroOptions, "save", "");
					if (!path.isEmpty()) {
						String extension = getFileExtension(path);
						String newpath = path.substring(0, path.lastIndexOf(extension)-1)+"-"+timestamp+"."+extension;
						Macro.setOptions(macroOptions.substring(0, macroOptions.indexOf(path)) + newpath + macroOptions.substring(macroOptions.indexOf(path)+path.length()));
					}
				}
			}
		}
		
		
		
		SaveDialog	sd = new SaveDialog("Save Video File As", suggestedFileName, "." + suggestedExtension);	
		String fileName = sd.getFileName();
		if (fileName == null) return false;
		String fileDir = sd.getDirectory();
		filePath = fileDir + fileName;
		String selectedExtension = getFileExtension(filePath);
		
		if (!selectedExtension.equals(suggestedExtension) && 
				av_guess_format(selectedExtension, filePath, null) == null) {
			IJ.showMessage("Error", "Not supported output format [" + selectedExtension.toUpperCase() + "]");
			return false;
		}
		
		
		
		if(!IsEncoderCompatible(suggestedExtension, codecCode, customVEnc)){
			IJ.showMessage("Error", "Selected encoder " + codec + " cannot be used with the selected output format ["+suggestedExtension.toUpperCase()+"]");
			return false;
		}
		
		vFormat = selectedExtension;
		
		logLevel=gd.getNextChoiceIndex();
		av_log_set_level(logLevCodes[logLevel]);
		
		//save settings 
		if (!IJ.isMacro()){
			Prefs.set("ffmpegvideoimport.savedFormatIndex", formatCode);
			Prefs.set("ffmpegvideoimport.savedEncoderIndex", codecCode);
			Prefs.set("ffmpegvideoimport.savedCustomEncoder", customVEnc);
			Prefs.set("ffmpegvideoimport.savedShowProgressOption", progressByStackUpdate);
			Prefs.set("ffmpegvideoimport.savedTimeStampOption", addTimeStamp);
		}

		IJ.register(this.getClass());
		return true;
	}
	
	
	public ImageStack getStack() {
		return stack;
	}
	
	/**Suggests file extension for a combination of format (formatCode)
	 * and encoder (codecCode or customVEnc). Returns empty string if
	 * the extension should be defined by later, at save dialog.  
	 * @param formatCode
	 * @param codecCode
	 * @param customVEnc
	 * @return
	 */
	String getSuggestedExtension(int formatCode, int codecCode, String customVEnc) {
		String suggestedExtension="";
		if (formatCode>0) {
			if (!formats[formatCode].equals(formatByExt)) suggestedExtension = formats[formatCode].toLowerCase();
		}
		else {
			suggestedExtension = defaultFormats.get(encoders[codecCode]);
			if (encCodes[codecCode]==-1) {
				LinkedHashSet<String> compatExt = new LinkedHashSet<String>();
				AVCodec enc = avcodec_find_encoder_by_name(customVEnc);
				int id = enc.id();
				for(AVOutputFormat oformat : ffmpegFormats){
					int[] r = EncoderFromatCompliance(id, oformat);
					if ( r[2]==1) {
						String longname = oformat.long_name()!=null?oformat.long_name().getString():"";
						if (longname.toLowerCase().indexOf("raw ")!=-1)continue;
						String extensions = oformat.extensions()!=null?oformat.extensions().getString():" ";
						compatExt.addAll(Arrays.asList(extensions.split(",")));
					}
						
				}
				if (compatExt.size()==0) suggestedExtension=defaultFormatForGuess;
				else {
					if (compatExt.contains(customVEnc)) {
						suggestedExtension = customVEnc;
					} else {
						Iterator<String> iter =  compatExt.iterator();
						suggestedExtension = iter.next();
						for (String ext : preferredExtensions){
							if (compatExt.contains(ext)) {
								suggestedExtension = ext;
								break;
							}
						}
					}
				}
				
			}
		}
		return suggestedExtension;
	}
	
	/** Encodes a stack into a video with default and specified parameters 
	 * frame dimensions are resized proportionally to give the desired width
	 * The function uses standard working scheme:
	 * 1. InitRecorder
	 * 2. EncodeFrame in a cycle running through the specified stack range 
	 * 3. StopRecorder
	 *   @param path the path of the resulting video file
	 *    
	 */
	public void RecordVideo(String path, ImagePlus imp, int desiredWidth, 
			double frameRate, int bRate, int firstSlice, int lastSlice){
		if (imp==null) return;

		ImageStack stack = imp.getStack();
		if (stack==null || stack.getSize() < 2 || stack.getProcessor(1)==null) {
			IJ.log("Nothing to encode as video. Stack is required with at least 2 slices.");
			initialized = false;
			return;
		}

		if (firstSlice>lastSlice || firstSlice>stack.getSize()){
			IJ.log("Incorrect slice range");
			initialized = false;
			return;
		}

		if (!InitRecorder(path, imp.getWidth(), imp.getHeight(), 
				desiredWidth, frameRate, bRate, vFormat, codecCode, customVEnc, vKeys, vOptions)) return;

		int start = firstSlice<0?1:firstSlice;
		int finish = lastSlice>stack.getSize()?stack.getSize():lastSlice;

		int lastUpdate = 1; int recLength = finish - start + 1;
		for (int i=start; i<finish+1; i++) {
			EncodeFrame(stack.getProcessor(i));
			int update = (i-start+1) * 20 / recLength;
			if(update > lastUpdate) {
				IJ.showProgress(update * 5);
				lastUpdate = update;
			}
			if (progressByStackUpdate) imp.setSlice(i);
		}

		try {
			StopRecorder();
		} catch (Exception e) {
			e.printStackTrace();
		}
		IJ.log("The video encoding is complete. File path:\n"+path);

	}
	
	
	private String getFileExtension(String path) {
		String extension = "";
		if (path!=null && !path.isEmpty()){
			int i = path.lastIndexOf('.');
			if (i > 0 && i < path.length() - 1) 
				extension = path.substring(i+1);
		}
		    return extension.toLowerCase();
	}
	
	static int[] EncoderFromatCompliance(int enc_id, AVOutputFormat format){
		
		return new int[]{avformat_query_codec(format, enc_id, FF_COMPLIANCE_VERY_STRICT),
				avformat_query_codec(format, enc_id, FF_COMPLIANCE_STRICT),
				avformat_query_codec(format, enc_id, FF_COMPLIANCE_NORMAL),
				avformat_query_codec(format, enc_id, FF_COMPLIANCE_UNOFFICIAL),
				avformat_query_codec(format, enc_id, FF_COMPLIANCE_EXPERIMENTAL)};
	}
	
	static boolean IsEncoderCompatible(String format, int enc_id) {
		AVOutputFormat oformat = av_guess_format(format, "video."+format, null);
		if (oformat==null) return false;
		int compatibility = EncoderFromatCompliance(enc_id, oformat)[2];//avformat_query_codec(oformat, enc_id, FF_COMPLIANCE_NORMAL);
		if (compatibility<0) IJ.log("Warning: Format/encoder compatibility is unknown.");
		return 0 != compatibility;
	}
	
	static boolean IsEncoderCompatible(String format, String enc) {
		AVCodec codec = avcodec_find_encoder_by_name(enc);
		return codec != null && IsEncoderCompatible(format , codec.id());
	}
	
	static boolean IsEncoderCompatible(String format, int codecCode, String customVEnc) {
		if ((encCodes[codecCode]<0 && !IsEncoderCompatible(format, customVEnc)) ||
				(encCodes[codecCode]>0 && !IsEncoderCompatible(format, encCodes[codecCode]))) {
			return false;
		}
		return true;
	}
	
	static boolean IsCodecSupported(int codecCode, String customVEnc) {
		if ((encCodes[codecCode]<0 && avcodec_find_encoder_by_name(customVEnc) == null) ||
				(encCodes[codecCode]>0 && avcodec_find_encoder(encCodes[codecCode]) == null)) {
				return false;
			}
		return true;
	}

	/** Initializes and starts FFmpegFrameRecorder with default settings:
	 * framerate = 25 fps, bitrate (automatically estimated to give high quality), 
	 * video codec (MPEG-4 simple profile), video format "avi", pixel format YUV420P, 
	 * gop size = 10, and other codec options are defaults.
	 * Video frame is proportionally rescaled from the initial dimensions of srcImp 
	 * to give desired frame width.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param srcImp ImagePlus instance providing initial dimensions of image
	 *  @param vWidth desired width of video frame. 
	 */
	public boolean InitRecorder(String path, ImagePlus srcImp, int vWidth){
		return initialized = InitRecorder(path, srcImp, vWidth, 25.0, 
				(int) (vWidth*vWidth*srcImp.getHeight()*1024.0/614400/srcImp.getWidth()));
	}

	/** Initializes and starts FFmpegFrameRecorder with default settings:
	 * video codec (MPEG-4 simple profile), video format "avi", pixel format YUV420P, 
	 * gop size = 10, and other codec options are defaults.
	 * Video frame is proportionally rescaled from the initial dimensions of srcImp 
	 * to give desired frame width.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param srcImp ImagePlus instance providing initial dimensions of image
	 *  @param vWidth desired width of video frame. 
	 *  @param frameRate desired framerate in fps
	 *  @param bRate desired bitrate in bps
	 */
	public boolean InitRecorder(String path, ImagePlus srcImp, int vWidth, double frameRate, int bRate) {
		
		return initialized = InitRecorder(path, srcImp.getWidth(), srcImp.getHeight(), vWidth, frameRate, bRate);
	}
	
	
	/** Initializes and starts FFmpegFrameRecorder with default settings:
	 * framerate = 25 fps, bitrate (automatically estimated to give high quality), 
	 * video codec (MPEG-4 simple profile), video format "avi", pixel format YUV420P, 
	 * gop size = 10, and other codec options are defaults.
	 * Video frame is rescaled from dimensions of initial image   
	 * to give desired frame width and height.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param vWidth desired width of video frame. 
	 *  @param vHeight desired height of video frame.
	 */
	public boolean InitRecorder(String path, int vWidth, int vHeight) {   	
		return initialized = InitRecorder(path, vWidth, vHeight, 25.0, (int) (vWidth*vHeight*1024.0/614400));
	}
	
	/** Initializes and starts FFmpegFrameRecorder with default settings:
	 * framerate = 25 fps, bitrate (automatically estimated to give high quality), 
	 * video codec (MPEG-4 simple profile), video format "avi", pixel format YUV420P, 
	 * gop size = 10, and other codec options are defaults.
	 * Video frame is proportionally rescaled from the specified initial dimensions 
	 * to give desired frame width.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param srcWidth width of initial image
	 *  @param srcHeight height of initial image
	 *  @param vWidth desired width of video frame. 
	 */
	public boolean InitRecorder(String path, int srcWidth, int srcHeight, int vWidth) {
		return initialized = InitRecorder(path, srcWidth, srcHeight, vWidth, 25.0, 
				(int) (vWidth*vWidth*srcHeight*1024.0/614400/srcWidth));
	}
	
	/** Initializes and starts FFmpegFrameRecorder with default settings:
	 * video codec (MPEG-4 simple profile), video format "avi", pixel format YUV420P, 
	 * gop size = 10, and other codec options are defaults.
	 * Video frame is proportionally rescaled from the specified initial dimensions 
	 * to give desired frame width.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param srcWidth width of initial image
	 *  @param srcHeight height of initial image
	 *  @param vWidth desired width of video frame. 
	 *  @param frameRate desired framerate in fps
	 *  @param bRate desired bitrate in bps
	 */
	public boolean InitRecorder(String path, int srcWidth, int srcHeght, int vWidth, double frameRate, int bRate) {
		if (vWidth<8){
			IJ.log("Incorrect output width");
			initialized = false;
			return false;
		}
		
		if (srcWidth<8 || srcHeght<8){
			IJ.log("Incorrect source dimentions");
			initialized = false;
			return false;
		}
		frameWidth = srcWidth;
		frameHeight = srcHeght;
		videoWidth=vWidth + (vWidth%8==0?0:(8-vWidth%8));
		int videoHeightProp = (frameHeight*videoWidth)/frameWidth;
		int videoHeightBorder = videoHeightProp%8==0?0:(8-videoHeightProp%8);
		videoHeight = videoHeightProp + videoHeightBorder;
		frameHeightBorder = (videoHeightBorder*frameWidth)/videoWidth;
		if (videoHeight<8){
			IJ.log("Incorrect output height");
			initialized = false;
			return false;
		}

		return initialized = InitRecorder(path, videoWidth, videoHeight, frameRate, bRate);
	}
	
	/** Initializes and starts FFmpegFrameRecorder with default settings:
	 * video codec (MPEG-4 simple profile), video format "avi", pixel format YUV420P, 
	 * gop size = 10, and other codec options are defaults.
	 * Video frame is rescaled from dimensions of initial image   
	 * to give desired frame width and height.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param vWidth desired width of video frame. 
	 *  @param vHeight desired height of video frame.
	 *  @param frameRate desired framerate in fps
	 *  @param bRate desired bitrate in bps
	 */
	public boolean InitRecorder(String path, int vWidth, int vHeight, double frameRate, int bRate) {
		
		
		if (vWidth<8 || vHeight<8){
    		IJ.log("Incorrect output dimensions");
    		initialized = false;
    		return false;
    	}
    		
    	videoWidth=vWidth + (vWidth%8==0?0:(8-vWidth%8));
    	videoHeight=vHeight + (vHeight%8==0?0:(8-vHeight%8));
    	
		return initialized = InitRecorder(path, videoWidth, videoHeight, 
				AV_PIX_FMT_NONE, frameRate, bRate, AV_CODEC_ID_MPEG4, null, "avi",
				10, null, null);
	}
	
	
	/** Initializes and starts FFmpegFrameRecorder with default pixel format YUV420P,
	 * while other settings are customized:
	 * video format (0 is by file extension), video codec (0 is by format), 
	 * custom codec can be specified, as well as custom codec options.
	 * Video frame is proportionally rescaled from the specified initial dimensions 
	 * to give desired frame width.  
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param srcWidth width of initial image
	 *  @param srcHeight height of initial image
	 *  @param vWidth desired width of video frame. 
	 *  @param frameRate desired framerate in fps
	 *  @param bRate desired bitrate in bps
	 *  @param format_code format code
	 *  @param vcodec index of the video codec in FFmpeg library
	 *  @param codec_code codec code 
	 *  @param vKeys a list of additional video option keys
	 *  @param vOptions a list of corresponding options  
	 */
	private boolean InitRecorder(String path, int srcWidth, int srcHeght, 
			int vWidth, double frameRate, int bRate, String video_format, int codec_code, String v_codec_custom,
			ArrayList<String> vKeys, ArrayList<String> vOptions) {
		if (vWidth<8){
			IJ.log("Incorrect output width");
			initialized = false;
			return false;
		}
		if (srcWidth<8 || srcHeght<8){
			IJ.log("Incorrect source dimensions");
			initialized = false;
			return false;
		}
		
		frameWidth = srcWidth;
		frameHeight = srcHeght;
		videoWidth = vWidth + (vWidth%8==0?0:(8-vWidth%8));
		
//		frameWidthBorder = 0;
//		frameHeightBorder = 0;
//		videoWidth = frameWidth;
//		videoHeight = frameHeight;
		
		if (vWidth!=srcWidth) {
			int videoHeightProp = (frameHeight*videoWidth)/frameWidth;
			int videoHeightBorder = videoHeightProp%8==0?0:(8-videoHeightProp%8);
			videoHeight = videoHeightProp + videoHeightBorder;
			frameHeightBorder = (videoHeightBorder*frameWidth)/videoWidth;
			frameWidthBorder = 0;
		} else {
			frameWidthBorder = videoWidth - vWidth;
			frameHeightBorder = frameHeight%8==0?0:(8-frameHeight%8);
			videoHeight = frameHeight + frameHeightBorder;
		}
		if (videoHeight<8){
			IJ.log("Incorrect output height");
			initialized = false;
			return false;
		}
		int v_codec = encCodes[codec_code];
		return initialized = InitRecorder(path, videoWidth, videoHeight, 
				AV_PIX_FMT_NONE, frameRate, bRate, v_codec, v_codec_custom, video_format,
				0,  vKeys, vOptions);
	}
	
	/** Initializes and starts FFmpegFrameRecorder with customized settings:
	 * Video frame dimensions are aligned to 8 pixel.
	 *  @param path   path to the resulting video file
	 *  @param vWidth desired width of video frame. 
	 *  @param vHeight desired height of video frame.
	 *  @param pixFmt pixel format of encoded frames
	 *  @param frameRate desired framerate in fps
	 *  @param bRate desired bitrate in bps
	 *  @param vcodec index of the video codec in FFmpeg library
	 *  @param vFmt format of video (avi, mkv, mp4, etc.)
	 *  @param gopSze the gop size
	 *  @param vKeys a list of additional video option keys
	 *  @param vOptions a list of corresponding options
	 */
	public boolean InitRecorder(String path, int vWidth, int vHeight, 
			int pixFmt, double frameRate, int bRate, int vcodec, String vcodecName, String vFmt,
			int gopSize, ArrayList<String> vKeys, ArrayList<String> vOptions) {
		
		if (initialized) {
			try {
				close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		filePath = path;
		videoWidth = vWidth;
		videoHeight = vHeight;
		frame_ARGB = null;
		fps=frameRate;
		bitRate=bRate;
		converter = new Java2DFrameConverter();
		try {
			recorder = FFmpegFrameRecorder.createDefault(path, vWidth, vHeight);
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		
		/* auto detect the output format from the name. */
		AVOutputFormat oformat;
        String format_name = vFmt == null || vFmt.length() == 0 ? null : vFmt;
        if ((oformat = av_guess_format(format_name, path, null)) == null) {
            int proto = path.indexOf("://");
            if (proto > 0) {
                format_name = path.substring(0, proto);
            }
            if ((oformat = av_guess_format(format_name, path, null)) == null) {
            	IJ.showMessage("Video output format error");
            }
        }
        format_name = oformat.name().getString();
		
		recorder.setFormat(format_name);
		
		AVCodec enc;
		if (vcodec>AV_CODEC_ID_NONE) {
			enc = avcodec_find_encoder(vcodec);
			recorder.setVideoCodec(vcodec);
		}
		else if (vcodec == -1) {
			enc = avcodec_find_encoder_by_name(vcodecName);
			recorder.setVideoCodecName(vcodecName);
		}
		else {
			int venc = av_guess_codec(oformat, null, path, null, AVMEDIA_TYPE_VIDEO);
			enc = avcodec_find_encoder(venc);
			recorder.setVideoCodec(venc);
		}
		
		if (pixFmt>=0) recorder.setPixelFormat(pixFmt);
		else {
			IntPointer pixFormats = enc.pix_fmts();
            int selectedPixFormat =  AV_PIX_FMT_NONE;
            int i = 0;
            while (true) {
                int pf = pixFormats.get(i++);

                // always prefer yuv420p, if available
                if(pf == AV_PIX_FMT_YUV420P) {
                    selectedPixFormat = AV_PIX_FMT_YUV420P;
                    break;
                }
                if(pf ==  AV_PIX_FMT_NONE) {
                    selectedPixFormat = avcodec_find_best_pix_fmt_of_list(pixFormats, AV_PIX_FMT_ARGB, 0, null);
                    break;
                }
            }
            recorder.setPixelFormat(selectedPixFormat);
		}
		if (frameRate>0) recorder.setFrameRate(frameRate); 
		if (bRate>0) recorder.setVideoBitrate(bRate);
		if (gopSize>0) recorder.setGopSize(gopSize);
		if (vKeys!=null && vOptions!=null && !vKeys.isEmpty() && vKeys.size()==vOptions.size()) 
			for (int i=0; i<vKeys.size(); i++) recorder.setVideoOption(vKeys.get(i), vOptions.get(i));
		
		recorder.setImageScalingFlags(SWS_SPLINE);
		
		try {
			recorder.start();
		} catch (Exception e2) {
			
			try {
				initialized = false;
				recorder.release();
			} catch (Exception e) {
				e.printStackTrace();
			}
			IJ.log("FFmpeg encoder not starting for some reason");
			e2.printStackTrace();
			return false;
		}
		initialized = true;
		return true;
	}
	
	
	
	/** Stops record and releases resources.
	 * Should be called at the end of record. 
	 */
	public void StopRecorder() throws Exception {
		recorder.stop();//.close();
		initialized = false;
	}
	
	
	/** Encodes one frame (next frame of the video)
	 * The image will be transformed to RGB if necessary, then encoded with
	 * parameters specified in a InitRecorder(...) function
	 *  @param ip ImageProcessor of the image to encode 
	 */
	public void EncodeFrame(ImageProcessor ip){
		if (frameHeightBorder!=0 || frameWidthBorder!=0) frame_ARGB = 
			converter.convert(
//				((new CanvasResizer()).expandImage(ip, frameWidth, frameHeight+frameHeightBorder, 
//											0, frameHeightBorder/2)).convertToRGB().getBufferedImage());
					expandImage(ip, frameWidth + frameWidthBorder, frameHeight + frameHeightBorder, 
							frameWidthBorder/2, frameHeightBorder/2).convertToRGB().getBufferedImage());
		else frame_ARGB = converter.convert(ip.convertToRGB().getBufferedImage());
		
		try {
			recorder.recordImage(frame_ARGB.imageWidth, frame_ARGB.imageHeight, Frame.DEPTH_UBYTE, 
					4, frame_ARGB.imageStride, AV_PIX_FMT_ARGB, frame_ARGB.image);
		} catch (Exception e1) {
			e1.printStackTrace();
		}
	}
	
	private ImageProcessor expandImage(ImageProcessor ipOld, int wNew, int hNew, int xOff, int yOff) {
		ImageProcessor ipNew = ipOld.createProcessor(wNew, hNew);
		ipNew.setValue(0.0);
		ipNew.fill();
		ipNew.insert(ipOld, xOff, yOff);
		return ipNew;
	}
	
	

	public double getFrameRate() {
		return fps;
	}

	public boolean isInitialized() {
		return initialized;
	}

	@Override
	public void close() throws Exception {
		if (recorder!=null){
			frameHeightBorder = 0;
			initialized = false;
			recorder.close();
			
		}
		
	}
	
	@Override
	protected void finalize() throws Throwable{
		close();
		super.finalize();
	}

	
}

class SetupDialog  extends NonBlockingGenericDialog {
	
	public Choice codecChoice;
	public Choice formatChoice;
	public TextField customEncField;
	public TextField firstSliceField;
	public TextField lastSliceField;
	FFmpeg_FrameRecorder recorder;
	
	public SetupDialog(String title, FFmpeg_FrameRecorder recorder) {
		super(title);
		this.recorder = recorder;
	}
	
	public void actionPerformed(ActionEvent e) {
		Object source = e.getSource();
		if (Macro.getOptions()==null && componentsAreSet() && source==getButtons()[0]) {
			int firstSlice = 0, lastSlice = 0;
			Double d = getValue(firstSliceField.getText());
			if (d!=null)
				firstSlice = (int)Math.abs(d.doubleValue());
			else {
				IJ.showMessage("Error", "Incorrect number format of the first slice");
				return;
			}
			
			d = getValue(lastSliceField.getText());
			if (d!=null)
				lastSlice = (int)Math.abs(d.doubleValue());
			else {
				IJ.showMessage("Error", "Incorrect number format of the last slice");
				return;
			}
			
			int stackSize = recorder.getStack().getSize();
			if (firstSlice<1) firstSlice=1;
			if (lastSlice>stackSize) lastSlice=stackSize;
			if (lastSlice<=firstSlice) {
				IJ.showMessage("Error", "Incorrect slice range (the last should be greater than the first)");
				return;
			}
			int formatCode = formatChoice.getSelectedIndex();
			int codecCode= codecChoice.getSelectedIndex();
			String customVEnc = customEncField.getText().trim().toLowerCase(Locale.US);
			
			
			if (FFmpeg_FrameRecorder.encCodes[codecCode]<0 && (customVEnc==null || customVEnc.isEmpty())) {
				IJ.showMessage("Error", "Custom encoder not specified");
				return;
			}
			
			String codec = "";
			if(FFmpeg_FrameRecorder.encCodes[codecCode]!=0) 
				codec = "[" + (FFmpeg_FrameRecorder.encCodes[codecCode]<0? 
						customVEnc:avcodec_find_encoder(FFmpeg_FrameRecorder.encCodes[codecCode]).name().getString()).toUpperCase()+"]";


			if(!FFmpeg_FrameRecorder.IsCodecSupported(codecCode, customVEnc)) {
				IJ.showMessage("Error", "Selected encoder " + codec + " is not supported");
				return;
			}
			
			if (!FFmpeg_FrameRecorder.formats[formatCode].equals(FFmpeg_FrameRecorder.formatByExt)) {
				
				String suggestedExtension = recorder.getSuggestedExtension(formatCode, codecCode, customVEnc);
				if(!FFmpeg_FrameRecorder.IsEncoderCompatible(suggestedExtension, codecCode, customVEnc)){
					IJ.showMessage("Error", "Selected encoder " + codec + " cannot be used with the selected output format ["+suggestedExtension.toUpperCase()+"]");
					return;
				}
			}
			
			
		}
		super.actionPerformed(e);
	}
	
	boolean componentsAreSet() {
		return codecChoice!=null &&
				formatChoice!=null && 
				customEncField!=null && 
				firstSliceField!=null && 
				lastSliceField!=null;
	}
	
}

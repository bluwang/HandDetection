import java.util.*;

import org.bytedeco.javacv.*;
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.opencv_core.MatVector;
import org.bytedeco.javacpp.opencv_video.KalmanFilter;
import org.bytedeco.javacpp.indexer.*;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;


public class Hand {
	
	private static final int IMG_SCALE = 2;  // scaling applied to webcam image
	
	private static final float SMALLEST_AREA =  600.0f; // ignore smaller contour areas
	
	private static final int MAX_POINTS = 20;   // max number of points stored in an array
	
	// used for simiplifying the defects list
	private static final int MIN_FINGER_DEPTH = 20;
	private static final int MAX_FINGER_ANGLE = 100;   // degrees
	
	// angle ranges of thumb and index finger of the left hand relative to its COG
	private static final int MIN_THUMB = 120;
	private static final int MAX_THUMB = 200;
	
	private static final int MIN_INDEX = 60;
	private static final int MAX_INDEX = 120;

	private static final int KCURVATURE = 8;
	
	// HSV ranges defining the glove colour
	private static final int hueLower=0, hueUpper=25;
	private static final int satLower=26, satUpper=150;
	private static final int briLower=60, briUpper=240;
	
	private static Mat hsvLower;
	private static Mat hsvUpper;
	
	  // defects data for the hand contour
	private ArrayList<Point> fingerTips;	

	
	//flag indicates whether hand is detected
	private static boolean detected;
	private static boolean printed;
	
	// hand details
	private Point cogPt;           // center of gravity (COG) of contour
	
	private Mat resultImg;
	private Mat hsvImg;
	private Mat imgThreshed;
	private Mat kernel;
	private MatVector contours;
	private Mat[] list;
	
	/******************Kalman Filter*********************************/
	private KalmanFilter KF;
	
	
	public Hand(int width, int height)
	{
		resultImg = new Mat(width,height,CV_8UC3);
		imgThreshed = new Mat(width,height,CV_8UC1);
		kernel=new Mat(8, 8, CV_8U, new Scalar(1d));//opencv erode and dilate kernel
		
		hsvImg = new Mat(width,height,CV_8UC3);
		hsvLower = new Mat(width,height,CV_8UC3,new Scalar(hueLower, satLower, briLower,0));
		hsvUpper = new Mat(width,height,CV_8UC3,new Scalar(hueUpper, satUpper, briUpper,0));
		contours = new MatVector();
		list= new Mat[2];
		
		cogPt = new Point();
	    fingerTips = new ArrayList<Point>();		
		
/******************initialize Kalman Filter*********************************/
		KF = new KalmanFilter(4,2,0,CV_32F);
		
		
		float[] f ={1,0,1,0,   0,1,0,1,  0,0,1,0,  0,0,0,1};

		FloatPointer p= new FloatPointer(f);
		Mat F=new Mat(4,4,CV_32F,p);
		Mat Fcopy=F.clone();//copy transition matrix to prevent memory recycling float[]f
		KF.transitionMatrix(Fcopy);
		
		Mat H=new Mat(2, 4,CV_32F);
		setIdentity(H);
		KF.measurementMatrix(H);
		
		Mat Q=new Mat(4, 4,CV_32F);
		setIdentity(Q,Scalar.all(1e-4));
		KF.processNoiseCov(Q);
		
		Mat R=new Mat(2, 2,CV_32F);
		setIdentity(R,Scalar.all(1e-2));
		KF.measurementNoiseCov(R);
		
		Mat P=new Mat(4, 4,CV_32F);
		setIdentity(P,Scalar.all(1));
		KF.errorCovPost(P);
/************************************************************************/		
		
		printed=false;
	}
	
	public void update(Mat im)
	{
		cvtColor(im, hsvImg, CV_BGR2HSV);
		inRange(hsvImg, hsvLower, hsvUpper, imgThreshed);
		im.copyTo(resultImg);
		erode(imgThreshed,imgThreshed,kernel);
		dilate(imgThreshed, imgThreshed, kernel);
		
		list[0]=findBiggestContour(imgThreshed);
		if(list[0]==null)
		{
			detected=false;
			return;
		}
		detected=true;
		
		extractCog(list[0]);
		
		findFingerTips(list[0]);
		
		
/******************update Kalman Filter***********************************************/		
		Mat measurement=new Mat(2,1,CV_32F);
		Mat estimated=new Mat(4,1,CV_32F);	
		Mat prediction=new Mat(4,1,CV_32F);
		
		FloatBufferIndexer pIdx=measurement.createIndexer();		
		pIdx.put(0, cogPt.x());
		pIdx.put(1, cogPt.y());
		
		estimated=KF.correct(measurement);		
//		pIdx=estimated.createIndexer();		
//		Point statePt=new Point((int)pIdx.get(0),(int)pIdx.get(1));	
//		circle(resultImg,statePt,6,new Scalar(255,0,0,0),-1,8,0);		

		prediction=KF.predict();		
		pIdx = prediction.createIndexer();		
		Point predictPt=new Point((int)pIdx.get(0),(int)pIdx.get(1));		
		circle(resultImg,predictPt,6,new Scalar(255,0,0,0),-1,8,0);		
		
/*****************display the result*******************************************/		
		MatVector contourList = new MatVector(list);
	
		RNG rng=new RNG(123456); //openCV Random Number Generator  set seed 123456	
		
		for( int i = 0; i< contourList.size(); i++ )
		{
			Scalar color = new Scalar(rng.uniform(0, 255), rng.uniform(0,255), rng.uniform(0,255), 0);		
			drawContours(resultImg,
					contourList,
					i, color);
		}
		
		for(int i=0;i<getFingerNumber();i++)
		{
			circle(resultImg,fingerTips.get(i),8,new Scalar(0,0,255,0),-1,8,0);			
			line(resultImg,fingerTips.get(i),cogPt,new Scalar(0,255,255,0),2,8,0);
		}
		return;
	}
	
	private Mat findBiggestContour(Mat imgThreshed)
	{
		Mat bigContour = null;
		findContours(imgThreshed, contours, RETR_LIST, CHAIN_APPROX_NONE);
		
		float maxArea = SMALLEST_AREA;
		
		RotatedRect box;
		for(int idx=0;idx<contours.size();idx++)
		{
			box = minAreaRect(contours.get(idx));
			float area = box.size().height()*box.size().width();
			if(area>maxArea)
			{
				maxArea=area;
				bigContour=contours.get(idx);
			}			
		}
				
		
		return bigContour;
	}
	
	private void findFingerTips(Mat approxContour)
	{
	//	Mat approxContour = new Mat();
		Mat hull = new Mat();
		Mat defects = new Mat();
	//	approxPolyDP(bigContour,approxContour,1,true);
		convexHull(approxContour,hull,false,false);
		convexityDefects(approxContour, hull, defects);
		
		IntBufferIndexer hullIdx = hull.createIndexer();
		IntBufferIndexer contourIdx = approxContour.createIndexer();
		
		fingerTips.clear();
		
		int vertex=hullIdx.get(0,hull.rows()-1);
		Point prev=new Point(contourIdx.get(0,vertex,0),contourIdx.get(0,vertex,1));	
		for(int i=0;i<hull.rows();i++)
		{

			vertex=hullIdx.get(0,i);
			Point tip = new Point(contourIdx.get(0,vertex,0),contourIdx.get(0,vertex,1));
			
			if(tip.y()>cogPt.y()+20)continue;//remove point below cogPt
			if(dist(prev,tip)<40)continue;//remove too closed redundant points
			
			int tipAngle=kcurvature(vertex,approxContour,40);
			if(tipAngle>70)continue;//remove big angle

			fingerTips.add(tip);
			
			prev=tip;
		}
						
	    convexHull(approxContour,hull,false,true);
	    
	    list[0]=approxContour;
	    list[1]=hull;
	    
	}
	
	private int angleBetween(Point tip, Point next, Point prev)
	{
		int angle = Math.abs( (int)Math.round( 
                Math.toDegrees(
                      Math.atan2(next.x() - tip.x(), next.y() - tip.y()) -
                      Math.atan2(prev.x() - tip.x(), prev.y() - tip.y())) ));
		if (angle > 180)angle=360-angle;
		return angle;
	}
	
	private int kcurvature(int index,Mat contour,int k)
	{
		
		IntBufferIndexer contourIdx = contour.createIndexer();
		int total=contour.rows();	
		
		int prev=total+index-k;;

		if(prev>=total)
		{
			prev=prev-total;
		}

		int next=index+k;
		if(next>=total)
		{
			next=next-total;
		}
		
    	Point ptStart=new Point(contourIdx.get(0,prev,0),contourIdx.get(0,prev,1));
    	Point ptEnd=new Point(contourIdx.get(0,next,0),contourIdx.get(0,next,1));
    	Point ptFold=new Point(contourIdx.get(0,index,0),contourIdx.get(0,index,1));

//		line(resultImg,ptStart,ptFold,new Scalar(0,255,255,0));
//		line(resultImg,ptEnd,ptFold,new Scalar(0,255,255,0));
		
		return angleBetween(ptFold, ptStart, ptEnd);
	}
	
	private void extractCog(Mat bigContour)
	{
		Moments m = new Moments();
		m=moments(bigContour);
		double m00 = m.m00();
	    double m10 = m.m10();
	    double m01 = m.m01();
	    
	    if (m00 != 0)
	    {
	    	int xCenter = (int) Math.round(m10/m00);
	    	int yCenter = (int) Math.round(m01/m00);
	    	cogPt.x(xCenter);
	    	cogPt.y(yCenter);
	    	circle(resultImg,cogPt,6,new Scalar(0,255,0,0),-1,8,0);
	    }
	}
	
	private int dist(Point u,Point v)
	{
		return Math.abs(u.x()-v.x())+Math.abs(u.y()-v.y());
	}
	
	public Mat getResult()
	{
		return resultImg; 
	}
	
	public int getFingerNumber()
	{
		return fingerTips.size();
	}
	
	public boolean isDetected()
	{
		return detected;
	}

/******************debug function*********************************/
	public void printMat(Mat a)
	{
		if(printed)return;
		printed=true;
		System.out.println("Total= " + a.total());
		System.out.println("Rows= " + a.rows());
		System.out.println("Cols= "+ a.cols());
		System.out.println("Type= " + a.type());
		System.out.println("Channels= " + a.channels());
		
		FloatBufferIndexer fIdx = a.createIndexer();
		for(int y=0;y<a.rows();y++)
		{
			System.out.print("[");
			for(int x=0;x<a.cols();x++)
			{
				System.out.print(fIdx.get(y,x)+" ");
			}
			System.out.print("]\n");
		}
		
	}
}

package com.example.android.soundrecorder
//package cz.letalvoj.gpgpu.equalizer //Not needed I think, used to help with FFT.
import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import android.media.MediaPlayer
import android.view.View
import android.media.AudioRecord
import cz.letalvoj.gpgpu.fft.OpenCLFFTCalculator

class MainActivity : AppCompatActivity() {
    //Setup and constant values
    //SAMPLES is the most critical value and determines audio buffer size, may need to be adjusted.
    private val SAMPLES = 4096
    //Chucks determines sample compression for the FFT. This should stay 2 I think
    private val CHUNKS = 2
    //Establish values for things to be initialized later
    private var output: String? = null
    private var audioRecord: AudioRecord? = null
    private var state: Boolean = false
    private var recordingStopped: Boolean = false
    private var Timer: Timer? = Timer()
    private var mMediaPlayer: MediaPlayer = null
    private val fftCalculator = OpenCLFFTCalculator(SAMPLES)
    //OnCreate standard function
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //setup audioRecord
        audioRecord = AudioRecord(1,44100f,16,4,SAMPLES)
        // Alternate audio record parameters (1,22050f,16,2,200,4000000)
        //button code, unchanged from one of the code tutorials used.
        button_start_recording.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                val permissions = arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                ActivityCompat.requestPermissions(this, permissions,0)
            } else {
                startRecording()
            }
        }

        button_stop_recording.setOnClickListener{
            stopRecording()
        }

        button_pause_recording.setOnClickListener {
            pauseRecording()
        }
    }

    //added function to play sound based on the heart-rate type
    //
    private fun playSound(hr: Char?) {
        //Stops current sound, plays sound 1 for VFIB
        if (hr=='V'){
            mMediaPlayer!!.stop()
            mMediaPlayer!!.release()
            mMediaPlayer = null

            mMediaPlayer = MediaPlayer.create(this, R.raw.sound1)
            mMediaPlayer!!.isLooping = true
            mMediaPlayer!!.start()
        }
        //Stops current sound, plays sound 2 for Stopped Heart/ No HR detected
        if (hr=='S'){
            mMediaPlayer!!.stop()
            mMediaPlayer!!.release()
            mMediaPlayer = null

            mMediaPlayer = MediaPlayer.create(this, R.raw.sound2)
            mMediaPlayer!!.isLooping = true
            mMediaPlayer!!.start()
        }
        //Stops current sound, plays sound 3 for Normal Heart Rate
        if (hr=='N'){
            mMediaPlayer!!.stop()
            mMediaPlayer!!.release()
            mMediaPlayer = null

            mMediaPlayer = MediaPlayer.create(this, R.raw.sound3)
            mMediaPlayer!!.isLooping = true
            mMediaPlayer!!.start()
        }
        else{
        }

    }
    //Function to determine the heartbeat type
    //Mostly derived from FFT source code, and a simple summation algorithm
    private fun heartRate(spectrum: DoubleArray): Char? {
        var hr1: Char? = null
        val averaged = movingAverage(spectrum, 8, spectrum.size / 2)
        val width =1024.0
        var mean35: Double = 0.0
        var mean410: Double = 0.0
        //proportionality constant
        //parameters may need tuning kV should be less that kN universally, V-FIB produces much less force (and therefore audio amplitude) that Normal heart rhythm.
        val kV: Double = 0.8 //Constant of proportionality of minimum audio magnitude to consider detection of V-FIB/V-TAC
        val kN: Double = 1 //Constant of proportionality of minimum audio magnitude to consider detection of Normal heart beat.
        var sum35: Double = 0.0
        var sum410: Double = 0.0
        var c35: Double = 0.0
        var c410: Double = 0.0
        //hard coding range for 0-4Hz(mean35), or 4-10Hz(mean410)
        val r1 = 0
        val r2 = 1
        val r11 = 2
        val r22 = 4
        //Simple for loop summation/averaging algorithm
        for ((i, v) in averaged.withIndex()) {
            val x = width / averaged.size * i
            val y = (0.9 - v / 2)
            if (x>=r1){
                if(x<=r2){
                    c35=c35+1
                    sum35=sum35+y
                }

            }
            if (x>=r11){
                if(x<=r22){
                    c410=c410+1
                    sum410=sum410+y
                }

            }
        }
        mean35 =sum35/c35
        mean410 =sum410/c410
        //If ladder for determining heart-rate type.
        //compares mean in the 0-4 Hz (mean35) and 4-10 Hz (mean410) ranges.
        //This is the simplest proposed algorithm for determining V-FIB vs Normal HR.
        //This is statement indicates V-FIB/V-TAC Heartbeat
        if (mean35<mean410 && mean410>kV){
            hr1='V'
        }
        //This indicates a heartbeat in the normal range.
        if (mean35>=mean410 && mean35>kN) {
            hr1='N'
        }
        //This may need to be fixed. But for now this logic defaults to "the patients heart has stopped. Please check patient"
        //This is a safe default, it prevents the app from missing a heart stoppage.
        else {
            hr1='S'
        }
        return hr1

    }
    //Main app function on press Start button
    private fun startRecording() {
        //Try catch to handle common errors
        try {
            //starts while loop
            state = true
            //Start pulling audio into buffer
            audioRecord?.startRecording()
            //processing loop
            Thread {
                while (state) {
                    //Run the processing every 5 seconds
                    Handler().postDelayed({
                        //reset the data array and read into the array
                        var data = doubleArrayOf()
                        audioRecord.read(data,0,4096,0)
                        //Compute the FFT amplitude spectrum
                        val amplitudeSpectrum = fftCalculator.calculate(data).amplitude()
                        //Process the FFT to determine the heartbeat type
                        var hr=heartRate(amplitudeSpectrum)
                        //play audio based on heartbeat type
                        playSound(hr)
                    }, 5000)

                }
            }.start()

            Toast.makeText(this, "Heart Monitoring Started!", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    //Stop recording function.
    private fun stopRecording(){
        if(state){
            audioRecord?.stop()
            audioRecord?.release()//idk if I need this or what this does but it's here in case?
            state = false
        }else{
            Toast.makeText(this, "You are not recording right now!", Toast.LENGTH_SHORT).show()
        }
    }
    //PauseRecording function to keep UI the same as template. Pause recording is identical  to stop recording for this app.
    private fun pauseRecording(){
        if(state){
            audioRecord?.stop()
            audioRecord?.release()
            state = false
        }else{
            Toast.makeText(this, "You are not recording right now!", Toast.LENGTH_SHORT).show()
        }
    }
}

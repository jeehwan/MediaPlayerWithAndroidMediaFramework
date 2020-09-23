// from https://github.com/googlearchive/android-Camera2Video/blob/master/kotlinApp/Application/src/main/java/com/example/android/camera2video/AutoFitTextureView.kt

/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.navercorp.deview.mediaplayer

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val aspectRatio = ratioWidth.toFloat() / ratioHeight.toFloat()
        var initialWidth = MeasureSpec.getSize(widthMeasureSpec)
        var initialHeight = MeasureSpec.getSize(heightMeasureSpec)

        val horizPadding = paddingLeft + paddingRight
        val vertPadding = paddingTop + paddingBottom
        initialWidth -= horizPadding
        initialHeight -= vertPadding

        val viewAspectRatio = initialWidth.toDouble() / initialHeight
        val aspectDiff: Double = aspectRatio / viewAspectRatio - 1

        // stay size if the difference of calculated aspect ratio is small enough from specific value

        // stay size if the difference of calculated aspect ratio is small enough from specific value
        if (Math.abs(aspectDiff) > 0.01) {
            if (aspectDiff > 0) {
                // adjust heght from width
                initialHeight = (initialWidth / aspectRatio).toInt()
            } else {
                // adjust width from height
                initialWidth = (initialHeight * aspectRatio).toInt()
            }
            initialWidth += horizPadding
            initialHeight += vertPadding
            val widthMeasureSpec = MeasureSpec.makeMeasureSpec(initialWidth, MeasureSpec.EXACTLY)
            val heightMeasureSpec = MeasureSpec.makeMeasureSpec(initialHeight, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

//        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
//        val width = View.MeasureSpec.getSize(widthMeasureSpec)
//        val height = View.MeasureSpec.getSize(heightMeasureSpec)
//        if (ratioWidth == 0 || ratioHeight == 0) {
//            setMeasuredDimension(width, height)
//        } else {
//            if (width < ((height * ratioWidth) / ratioHeight)) {
//                setMeasuredDimension(width, (width * ratioHeight) / ratioWidth)
//            } else {
//                setMeasuredDimension((height * ratioWidth) / ratioHeight, height)
//            }
//        }
    }

}
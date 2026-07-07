package org.symera.mediasource.en.yflix

import org.symera.mediasource.multisrc.yflix.YFlixTheme

class YFlix :
    YFlixTheme(
        name = "YFlix",
        domainList = listOf(
            "yflix.cc",
            "flixhq.to",
            "myflixerz.to",
            "ymovies.cc",
        ),
        defaultDomain = "https://yflix.cc",
    )

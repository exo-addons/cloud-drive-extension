const path = require('path');
const merge = require('webpack-merge');
const webpackCommonConfig = require('./webpack.common.js');

// the display name of the war
const app = 'ecm-wcm-extension';

const config = merge(webpackCommonConfig, {
  output: {
    path: path.resolve(__dirname, `./target/${app}/`),
    filename: 'js/[name].bundle.js',
    libraryTarget: 'amd'
  }
});

module.exports = config;

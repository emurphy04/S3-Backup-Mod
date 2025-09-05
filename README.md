# S3 Backup Mod (Fabric • Server-Only)

![Green Minecraft Bucket](https://cdn.modrinth.com/data/cached_images/1ef5ba6809f320f0dd9adc8857a28bb8b813ae28.png)

Back up your Minecraft server world to Amazon S3 on a schedule — with an in-game setup wizard, a manual `/backupnow` command, and automatic pruning to **keep only the newest N backups**.

> ⚠️ **Pricing & Liability**  
> By downloading, installing, or using this mod, **you accept that the author is not liable for any AWS/S3 charges** (storage, requests, data transfer, etc.).  
> **Set your retention!** Configure `keepLastNS3` to avoid unlimited growth and surprise bills.

---

## Requirements

- **Minecraft:** Java Edition server **1.21.8**  
- **Fabric Loader** and **Fabric API (required)**  
- **Java 21**  
- **AWS account** with access to an S3 bucket (see **IAM Permissions** at the bottom)

---

## Features

- **Automatic scheduled backups** (default every 10 minutes)
- **Manual backups** via `/backupnow`
- **In-game setup wizard** to configure region, bucket, prefix, credentials, and retention
- **Retention control**: keep only the latest **N** backups in S3 (default **5**)
- **Non-blocking**: save occurs on the main thread; zipping & upload run on a background thread
- **Server-only**: no client entrypoint or client mixins

---

## Configuration

A non-secret config file is generated at:

```
  config/s3-backup-mod.json
```
```json
  {
    "backupIntervalMinutes": 30,
    "s3Bucket": "YOUR_BUCKET",
    "s3Prefix": "YOUR_PREFIX",
    "awsRegion": "us-east-1",
    "zipBaseName": "world-backup",
    "excludeGlobs": ["logs/**", "crash-reports/**", "backups/**", "*.log", "**/session.lock"],
    "keepLastNS3": 5,
    "keepLatestLocal": false,
    "deleteLocalAfterUpload": true,
    "multipartThresholdMB": 64,
    "multipartPartSizeMB": 256,
    "multipartParallelism": 4
  }
```

## Usage

In-game commands to run with admin permissions:

```
/s3setup set name YOUR_WORLD_NAME //Optional
/s3setup set interval 30          //Default is 30 minutes
/s3setup set bucket YOUR_BUCKET
/s3setup set prefix YOUR_PREFIX
/s3setup set region us-east-1
/s3setup set multipartThresholdMB 64
/s3setup set multipartPartSizeMB 512
/s3setup set multipartParallelism 4
/s3setup set keep 5
```
Extra commands:

```
/backupnow - Backs up the world to S3 when ran
/s3setup show - Shows configuration for mod in-game
```

## IAM Permissions
Ensure your credentials/role can write to the bucket and manage objects for pruning:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "s3:PutObject",
      "s3:AbortMultipartUpload",
      "s3:ListBucket",
      "s3:GetBucketLocation",
      "s3:ListBucketMultipartUploads",
      "s3:ListMultipartUploadParts",
      "s3:DeleteObject"
    ],
    "Resource": [
      "arn:aws:s3:::YOUR_BUCKET_NAME",
      "arn:aws:s3:::YOUR_BUCKET_NAME/*"
    ]
  }]
}

```

# S3 Backup Mod (Fabric • Server-Only)

![Green Minecraft Bucket](src/main/resources/assets/s3-backup-mod/GreenBucket.png)

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

> If other mods on your server use Kotlin, you’ll also need **Fabric Language Kotlin**. This mod itself does **not** require Kotlin.

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
    "backupIntervalMinutes": 10,
    "s3Bucket": "",
    "s3Prefix": "mc-backups",
    "awsRegion": "us-east-1",
    "keepLatestLocal": false,
    "deleteLocalAfterUpload": true,
    "zipBaseName": "world-backup",
    "excludeGlobs": ["logs/**", "backups/**", "crash-reports/**"],
    "keepLastNS3": 5
    }
```

## Usage

In-game commands to run with admin permissions:

```
/s3setup wizard
/s3setup set region us-east-1
/s3setup set bucket your-bucket-name
/s3setup set prefix mc-backups             # optional
/s3setup set keep 5                        # <-- how many backups to keep (very important)
/s3setup set accessKey AKIA................
/s3setup set secretKey <your-secret>
/s3setup set sessionToken <token>          # only if using temporary creds
/s3setup test
/s3setup finish
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
